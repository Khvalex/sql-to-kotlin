package sqlkt

import com.squareup.kotlinpoet.CodeBlock
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexInputRef
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexUtil
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.type.SqlTypeName
import java.math.BigDecimal

/**
 * Translates a scalar [RexNode] expression tree into a Kotlin expression.
 *
 * The produced expression evaluates to `Any?` (numbers/strings/booleans/null)
 * against a positional row `List<Any?>` bound to the variable named [rowVar].
 * SQL null semantics are implemented by the runtime prelude helpers, which all
 * accept `Any?`, so no casts are needed at call sites.
 */
class RexToKotlin(private val rexBuilder: RexBuilder) {

    fun toExpr(node: RexNode, rowVar: String = "row"): CodeBlock =
        // Expand Sarg-based SEARCH calls (IN-lists, BETWEEN, range predicates)
        // back into plain comparisons combined with AND/OR.
        translate(RexUtil.expandSearch(rexBuilder, null, node), rowVar)

    private fun translate(node: RexNode, rowVar: String): CodeBlock = when (node) {
        is RexInputRef -> CodeBlock.of("%L[%L]", rowVar, node.index)
        is RexLiteral -> literal(node)
        is RexCall -> call(node, rowVar)
        else -> throw UnsupportedOperationException(
            "Unsupported expression node ${node::class.simpleName}: $node " +
                "(window functions / correlated access that survived decorrelation are not supported)"
        )
    }

    private fun literal(lit: RexLiteral): CodeBlock {
        if (lit.isNull) return CodeBlock.of("null")
        return when (lit.type.sqlTypeName) {
            SqlTypeName.BOOLEAN -> CodeBlock.of("%L", lit.getValueAs(Boolean::class.javaObjectType))
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER ->
                CodeBlock.of("%L", lit.getValueAs(Int::class.javaObjectType))
            SqlTypeName.BIGINT -> CodeBlock.of("%LL", lit.getValueAs(Long::class.javaObjectType))
            SqlTypeName.DECIMAL -> {
                val v = lit.getValueAs(BigDecimal::class.java)!!
                if (v.scale() <= 0) CodeBlock.of("%L", v.longValueExact().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it })
                else CodeBlock.of("%L", v.toDouble())
            }
            SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE ->
                CodeBlock.of("%L", lit.getValueAs(Double::class.javaObjectType))
            SqlTypeName.CHAR, SqlTypeName.VARCHAR ->
                CodeBlock.of("%S", lit.getValueAs(String::class.java))
            else -> throw UnsupportedOperationException("Unsupported literal type: ${lit.type.sqlTypeName}")
        }
    }

    private fun call(call: RexCall, rowVar: String): CodeBlock {
        fun arg(i: Int) = translate(call.operands[i], rowVar)
        fun helper(name: String) = CodeBlock.of(
            "$name(${call.operands.indices.joinToString(", ") { "%L" }})",
            *call.operands.map { translate(it, rowVar) }.toTypedArray(),
        )

        return when (call.kind) {
            SqlKind.AND -> nAry("and3", call.operands, rowVar)
            SqlKind.OR -> nAry("or3", call.operands, rowVar)
            SqlKind.NOT -> CodeBlock.of("not3(%L)", arg(0))

            SqlKind.EQUALS -> helper("eq")
            SqlKind.NOT_EQUALS -> helper("neq")
            SqlKind.LESS_THAN -> helper("lt")
            SqlKind.LESS_THAN_OR_EQUAL -> helper("lte")
            SqlKind.GREATER_THAN -> helper("gt")
            SqlKind.GREATER_THAN_OR_EQUAL -> helper("gte")
            SqlKind.IS_NOT_DISTINCT_FROM -> helper("isNotDistinct")
            SqlKind.IS_DISTINCT_FROM -> helper("isDistinct")

            SqlKind.IS_NULL -> CodeBlock.of("(%L == null)", arg(0))
            SqlKind.IS_NOT_NULL -> CodeBlock.of("(%L != null)", arg(0))
            SqlKind.IS_TRUE -> CodeBlock.of("(%L == true)", arg(0))
            SqlKind.IS_NOT_TRUE -> CodeBlock.of("(%L != true)", arg(0))
            SqlKind.IS_FALSE -> CodeBlock.of("(%L == false)", arg(0))
            SqlKind.IS_NOT_FALSE -> CodeBlock.of("(%L != false)", arg(0))

            SqlKind.PLUS -> narrowNumeric(call, helper("numAdd"))
            SqlKind.MINUS -> narrowNumeric(call, helper("numSub"))
            SqlKind.TIMES -> narrowNumeric(call, helper("numMul"))
            SqlKind.DIVIDE -> narrowNumeric(call, helper("numDiv"))
            SqlKind.MOD -> narrowNumeric(call, helper("numMod"))
            SqlKind.MINUS_PREFIX -> narrowNumeric(call, CodeBlock.of("numNeg(%L)", arg(0)))
            SqlKind.PLUS_PREFIX -> arg(0)

            SqlKind.CASE -> caseWhen(call, rowVar)
            SqlKind.CAST -> cast(call.type.sqlTypeName, arg(0))

            SqlKind.LIKE -> helper("like")

            SqlKind.OTHER_FUNCTION, SqlKind.OTHER -> function(call, rowVar)

            else -> function(call, rowVar)
        }
    }

    /** Named functions and operators without a dedicated SqlKind. */
    private fun function(call: RexCall, rowVar: String): CodeBlock {
        fun helper(name: String) = CodeBlock.of(
            "$name(${call.operands.indices.joinToString(", ") { "%L" }})",
            *call.operands.map { translate(it, rowVar) }.toTypedArray(),
        )
        return when (call.operator.name.uppercase()) {
            "||", "CONCAT" -> helper("concatStr")
            "UPPER" -> helper("upper")
            "LOWER" -> helper("lower")
            "CHAR_LENGTH", "CHARACTER_LENGTH", "LENGTH" -> helper("charLength")
            "SUBSTRING" -> helper("substr")
            "ABS" -> narrowNumeric(call, helper("sqlAbs"))
            "MOD" -> narrowNumeric(call, helper("numMod"))
            "COALESCE" -> helper("coalesce") // normally rewritten to CASE by Calcite
            else -> throw UnsupportedOperationException("Unsupported SQL function/operator: ${call.operator.name}")
        }
    }

    private fun nAry(helper: String, operands: List<RexNode>, rowVar: String): CodeBlock =
        operands.map { translate(it, rowVar) }
            .reduce { acc, next -> CodeBlock.of("$helper(%L, %L)", acc, next) }

    /** CASE WHEN c1 THEN v1 ... ELSE e END -> Kotlin `when` expression. */
    private fun caseWhen(call: RexCall, rowVar: String): CodeBlock {
        val ops = call.operands
        val b = CodeBlock.builder().add("when {\n").indent()
        var i = 0
        while (i + 1 < ops.size) {
            b.add("truth(%L) -> %L\n", translate(ops[i], rowVar), translate(ops[i + 1], rowVar))
            i += 2
        }
        b.add("else -> %L\n", translate(ops.last(), rowVar))
        return b.unindent().add("}").build()
    }

    private fun cast(target: SqlTypeName, value: CodeBlock): CodeBlock = when (target) {
        SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER -> CodeBlock.of("asInt(%L)", value)
        SqlTypeName.BIGINT -> CodeBlock.of("asLong(%L)", value)
        SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE, SqlTypeName.DECIMAL -> CodeBlock.of("asDouble(%L)", value)
        SqlTypeName.CHAR, SqlTypeName.VARCHAR -> CodeBlock.of("asString(%L)", value)
        SqlTypeName.BOOLEAN -> CodeBlock.of("asBoolean(%L)", value)
        else -> throw UnsupportedOperationException("Unsupported CAST target type: $target")
    }

    /**
     * Arithmetic helpers compute in Long/Double; narrow the result back to the
     * Kotlin type matching the SQL-derived type of the expression, so that e.g.
     * INTEGER + INTEGER produces an Int at runtime.
     */
    private fun narrowNumeric(call: RexCall, expr: CodeBlock): CodeBlock = when (call.type.sqlTypeName) {
        SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER -> CodeBlock.of("asInt(%L)", expr)
        SqlTypeName.BIGINT -> CodeBlock.of("asLong(%L)", expr)
        SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE -> CodeBlock.of("asDouble(%L)", expr)
        SqlTypeName.DECIMAL ->
            // Exact decimals are approximated with Double unless the value is integral.
            if (call.type.scale > 0) CodeBlock.of("asDouble(%L)", expr) else CodeBlock.of("asLong(%L)", expr)
        else -> expr
    }
}
