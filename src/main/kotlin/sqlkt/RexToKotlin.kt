package sqlkt

import org.apache.calcite.avatica.util.TimeUnitRange
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.rex.RexCall
import org.apache.calcite.rex.RexDynamicParam
import org.apache.calcite.rex.RexInputRef
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexNode
import org.apache.calcite.rex.RexUtil
import org.apache.calcite.rex.RexVisitorImpl
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.type.SqlTypeName
import org.apache.calcite.util.DateString
import org.apache.calcite.util.TimeString
import org.apache.calcite.util.TimestampString
import java.math.BigDecimal

/**
 * Translation context for one generated lambda.
 *
 * @param bindings input ref index -> local variable name (`val salary = row[3]`)
 * @param shared   rex digest -> local variable holding a common subexpression
 * @param indent   absolute indentation of the expression, for multi-line `when`
 */
data class RexContext(
    val bindings: Map<Int, String> = emptyMap(),
    val shared: Map<String, String> = emptyMap(),
    val indent: String = "",
)

/**
 * Translates a scalar [RexNode] expression tree into Kotlin source text.
 *
 * Expressions evaluate to `Any?` against a positional row `List<Any?>`;
 * input refs resolve through [RexContext.bindings] so the generated code reads
 * `salary > 900.0` instead of `row[3] > ...`. SQL null semantics live in the
 * runtime prelude helpers, which all accept `Any?`.
 */
class RexToKotlin(private val rexBuilder: RexBuilder) {

    /** Expand Sarg-based SEARCH calls (IN-lists, BETWEEN, ranges) into plain comparisons. */
    fun expand(node: RexNode): RexNode = RexUtil.expandSearch(rexBuilder, null, node)

    /** Input ref indexes used by an (expanded) expression, ascending. */
    fun inputRefs(node: RexNode): Set<Int> {
        val refs = sortedSetOf<Int>()
        node.accept(object : RexVisitorImpl<Unit>(true) {
            override fun visitInputRef(inputRef: RexInputRef) {
                refs += inputRef.index
            }
        })
        return refs
    }

    /**
     * RexCall subtrees worth extracting into a local `val` (smallest first).
     *
     * A subtree qualifies if, after replacing occurrences of already-selected
     * larger subtrees with their locals, it is still computed at least twice.
     * This avoids the naive-CSE cascade where every nested level of a repeated
     * expression gets its own pointless local.
     */
    fun sharedSubtrees(exprs: List<RexNode>): List<RexNode> {
        class Info(val node: RexNode, val size: Int) {
            var count = 0
        }

        val infos = LinkedHashMap<String, Info>()
        fun walk(node: RexNode): Int {
            var size = 1
            if (node is RexCall) {
                node.operands.forEach { size += walk(it) }
                infos.getOrPut(node.toString()) { Info(node, size) }.count++
            }
            return size
        }
        exprs.forEach { walk(it) }

        val candidates = infos.values.filter { it.count >= 2 && it.size >= 3 }.sortedByDescending { it.size }
        val kept = mutableListOf<Info>()
        val keptDigests = mutableSetOf<String>()

        // Occurrences of [digest] in [root], treating selected subtrees as opaque
        // locals. [countRoot] is false when [root] is a selected subtree's own body.
        fun occurrences(root: RexNode, digest: String, countRoot: Boolean): Int {
            var n = 0
            fun visit(node: RexNode, isRoot: Boolean) {
                val d = node.toString()
                if (!(isRoot && !countRoot)) {
                    if (d == digest) {
                        n++
                        return
                    }
                    if (d in keptDigests) return
                }
                if (node is RexCall) node.operands.forEach { visit(it, false) }
            }
            visit(root, true)
            return n
        }

        for (c in candidates) {
            val digest = c.node.toString()
            val total = exprs.sumOf { occurrences(it, digest, countRoot = true) } +
                kept.sumOf { occurrences(it.node, digest, countRoot = false) }
            if (total >= 2) {
                kept += c
                keptDigests += digest
            }
        }
        return kept.sortedBy { it.size }.map { it.node }
    }

    /** Kotlin expression (type `Any?`) for an already [expand]ed node. */
    fun expr(node: RexNode, ctx: RexContext): String {
        ctx.shared[node.toString()]?.let { return it }
        return when (node) {
            is RexInputRef -> ctx.bindings[node.index] ?: "row[${node.index}]"
            is RexLiteral -> literal(node)
            // JDBC-style `?` placeholders: bound at runtime from the params list.
            is RexDynamicParam -> "(params[${node.index}] as ${kotlinType(node.type)})"
            is RexCall -> call(node, ctx)
            else -> throw UnsupportedOperationException(
                "Unsupported expression node ${node::class.simpleName}: $node " +
                    "(window functions / correlated access that survived decorrelation are not supported)"
            )
        }
    }

    /** Kotlin `Boolean` (non-null) expression, for filter/join conditions and `when` branches. */
    fun condition(node: RexNode, ctx: RexContext): String {
        val e = expr(node, ctx)
        return if (node.toString() !in ctx.shared && isNonNullBoolean(node)) stripOuterParens(e) else "truth($e)"
    }

    /** `(salary != null)` -> `salary != null` when the parens wrap the whole expression. */
    private fun stripOuterParens(e: String): String {
        if (!e.startsWith("(") || !e.endsWith(")")) return e
        var depth = 0
        for ((i, c) in e.withIndex()) {
            when (c) {
                '(' -> depth++
                ')' -> if (--depth == 0 && i < e.lastIndex) return e
            }
        }
        return e.substring(1, e.length - 1)
    }

    private fun isNonNullBoolean(node: RexNode): Boolean = when (node.kind) {
        SqlKind.IS_NULL, SqlKind.IS_NOT_NULL,
        SqlKind.IS_TRUE, SqlKind.IS_NOT_TRUE,
        SqlKind.IS_FALSE, SqlKind.IS_NOT_FALSE,
        SqlKind.IS_DISTINCT_FROM, SqlKind.IS_NOT_DISTINCT_FROM,
        -> true

        else -> false
    }

    private fun literal(lit: RexLiteral): String {
        if (lit.isNull) return "null"
        return when (lit.type.sqlTypeName) {
            SqlTypeName.BOOLEAN -> lit.getValueAs(Boolean::class.javaObjectType).toString()
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER ->
                lit.getValueAs(Int::class.javaObjectType).toString()
            SqlTypeName.BIGINT -> "${lit.getValueAs(Long::class.javaObjectType)}L"
            SqlTypeName.DECIMAL -> {
                val v = lit.getValueAs(BigDecimal::class.java)!!
                // Integral DECIMAL maps to Long (see kotlinType), so suffix with L.
                if (v.scale() <= 0) "${v.longValueExact()}L" else v.toDouble().toString()
            }
            SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE ->
                lit.getValueAs(Double::class.javaObjectType).toString()
            SqlTypeName.CHAR, SqlTypeName.VARCHAR ->
                kotlinString(lit.getValueAs(String::class.java)!!)
            SqlTypeName.DATE ->
                "java.time.LocalDate.parse(${kotlinString(lit.getValueAs(DateString::class.java).toString())})"
            SqlTypeName.TIME ->
                "java.time.LocalTime.parse(${kotlinString(lit.getValueAs(TimeString::class.java).toString())})"
            SqlTypeName.TIMESTAMP ->
                "java.time.LocalDateTime.parse(" +
                    kotlinString(lit.getValueAs(TimestampString::class.java).toString().replace(' ', 'T')) + ")"
            in SqlTypeName.YEAR_INTERVAL_TYPES ->
                // Year-month intervals are stored as a number of months.
                "java.time.Period.ofMonths(${lit.getValueAs(BigDecimal::class.java)!!.intValueExact()})"
            in SqlTypeName.DAY_INTERVAL_TYPES ->
                // Day-time intervals are stored as a number of milliseconds.
                "java.time.Duration.ofMillis(${lit.getValueAs(BigDecimal::class.java)!!.longValueExact()}L)"
            else -> throw UnsupportedOperationException("Unsupported literal type: ${lit.type.sqlTypeName}")
        }
    }

    private fun isDatetime(type: SqlTypeName): Boolean =
        type == SqlTypeName.DATE || type == SqlTypeName.TIME ||
            type == SqlTypeName.TIMESTAMP || type == SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE

    private fun isApprox(type: SqlTypeName): Boolean =
        type == SqlTypeName.DOUBLE || type == SqlTypeName.FLOAT || type == SqlTypeName.REAL

    private fun call(call: RexCall, ctx: RexContext): String {
        fun arg(i: Int) = expr(call.operands[i], ctx)
        fun helper(name: String) = "$name(${call.operands.joinToString(", ") { expr(it, ctx) }})"

        return when (call.kind) {
            SqlKind.AND -> call.operands.map { expr(it, ctx) }.reduce { a, b -> "and3($a, $b)" }
            SqlKind.OR -> call.operands.map { expr(it, ctx) }.reduce { a, b -> "or3($a, $b)" }
            SqlKind.NOT -> "not3(${arg(0)})"

            SqlKind.EQUALS -> helper("eq")
            SqlKind.NOT_EQUALS -> helper("neq")
            SqlKind.LESS_THAN -> helper("lt")
            SqlKind.LESS_THAN_OR_EQUAL -> helper("lte")
            SqlKind.GREATER_THAN -> helper("gt")
            SqlKind.GREATER_THAN_OR_EQUAL -> helper("gte")
            SqlKind.IS_NOT_DISTINCT_FROM -> helper("isNotDistinct")
            SqlKind.IS_DISTINCT_FROM -> helper("isDistinct")

            SqlKind.IS_NULL -> "(${arg(0)} == null)"
            SqlKind.IS_NOT_NULL -> "(${arg(0)} != null)"
            SqlKind.IS_TRUE -> "(${arg(0)} == true)"
            SqlKind.IS_NOT_TRUE -> "(${arg(0)} != true)"
            SqlKind.IS_FALSE -> "(${arg(0)} == false)"
            SqlKind.IS_NOT_FALSE -> "(${arg(0)} != false)"

            // `datetime + interval` keeps the PLUS/MINUS kind but a datetime type.
            // dtPlus/dtMinus are Any?-typed, so cast back to the derived type.
            SqlKind.PLUS ->
                if (isDatetime(call.type.sqlTypeName)) "(${helper("dtPlus")} as ${kotlinType(call.type)})"
                else narrow(call, helper("numAdd"))
            SqlKind.MINUS -> when {
                isDatetime(call.type.sqlTypeName) -> "(${helper("dtMinus")} as ${kotlinType(call.type)})"
                // `datetime - datetime` (MINUS_DATE operator, kind MINUS) yields an interval.
                call.type.sqlTypeName in SqlTypeName.YEAR_INTERVAL_TYPES -> "dtDiffMonths(${arg(0)}, ${arg(1)})"
                call.type.sqlTypeName in SqlTypeName.DAY_INTERVAL_TYPES -> "dtDiffDuration(${arg(0)}, ${arg(1)})"
                else -> narrow(call, helper("numSub"))
            }
            SqlKind.TIMES -> narrow(call, helper("numMul"))
            SqlKind.DIVIDE -> narrow(call, helper("numDiv"))
            SqlKind.MOD -> narrow(call, helper("numMod"))
            SqlKind.MINUS_PREFIX -> narrow(call, "numNeg(${arg(0)})")
            SqlKind.PLUS_PREFIX -> arg(0)

            SqlKind.EXTRACT -> {
                val unit = (call.operands[0] as RexLiteral).getValueAs(TimeUnitRange::class.java)!!.name
                "extractField(${kotlinString(unit)}, ${arg(1)})"
            }

            SqlKind.FLOOR, SqlKind.CEIL -> {
                // Two-operand form is FLOOR(datetime TO unit) — not supported.
                require(call.operands.size == 1) { "FLOOR/CEIL with a time unit is not supported" }
                val fn = if (call.kind == SqlKind.FLOOR) "numFloor" else "numCeil"
                narrow(call, "$fn(${arg(0)})")
            }

            SqlKind.CASE -> caseWhen(call, ctx)
            SqlKind.CAST -> cast(call, ctx)

            SqlKind.LIKE -> helper("like")

            else -> function(call, ctx)
        }
    }

    /** Named functions and operators without a dedicated SqlKind. */
    private fun function(call: RexCall, ctx: RexContext): String {
        fun helper(name: String) = "$name(${call.operands.joinToString(", ") { expr(it, ctx) }})"
        return when (call.operator.name.uppercase()) {
            "||", "CONCAT" -> helper("concatStr")
            "UPPER" -> helper("upper")
            "LOWER" -> helper("lower")
            "CHAR_LENGTH", "CHARACTER_LENGTH", "LENGTH" -> helper("charLength")
            "SUBSTRING" -> helper("substr")
            "ABS" -> narrow(call, helper("sqlAbs"))
            "MOD" -> narrow(call, helper("numMod"))
            "POWER" -> narrow(call, helper("numPower"), doubleValued = true)
            "SQRT" -> narrow(call, helper("numSqrt"), doubleValued = true)
            "EXP" -> narrow(call, helper("numExp"), doubleValued = true)
            "LN" -> narrow(call, helper("numLn"), doubleValued = true)
            "LOG10" -> narrow(call, helper("numLog10"), doubleValued = true)
            "SIN" -> narrow(call, helper("numSin"), doubleValued = true)
            "COS" -> narrow(call, helper("numCos"), doubleValued = true)
            "TAN" -> narrow(call, helper("numTan"), doubleValued = true)
            "COT" -> narrow(call, helper("numCot"), doubleValued = true)
            "ASIN" -> narrow(call, helper("numAsin"), doubleValued = true)
            "ACOS" -> narrow(call, helper("numAcos"), doubleValued = true)
            "ATAN" -> narrow(call, helper("numAtan"), doubleValued = true)
            "ATAN2" -> narrow(call, helper("numAtan2"), doubleValued = true)
            "DEGREES" -> narrow(call, helper("numDegrees"), doubleValued = true)
            "RADIANS" -> narrow(call, helper("numRadians"), doubleValued = true)
            "SIGN" -> narrow(call, helper("numSign"))
            "ROUND" -> narrow(call, helper("numRound"))
            "TRUNCATE" -> narrow(call, helper("numTruncate"))
            "PI" -> "kotlin.math.PI"
            "COALESCE" -> helper("coalesce") // normally rewritten to CASE by Calcite
            "CURRENT_DATE" -> "java.time.LocalDate.now()"
            "CURRENT_TIMESTAMP", "LOCALTIMESTAMP" -> "java.time.LocalDateTime.now()"
            "CURRENT_TIME", "LOCALTIME" -> "java.time.LocalTime.now()"
            else -> throw UnsupportedOperationException("Unsupported SQL function/operator: ${call.operator.name}")
        }
    }

    /**
     * CASE -> `if (c) a else b` for a single branch, multi-line `when` otherwise.
     * [RexContext.indent] is the absolute indentation of the expression.
     */
    private fun caseWhen(call: RexCall, ctx: RexContext): String {
        val ops = call.operands
        if (ops.size == 3) {
            return "if (${condition(ops[0], ctx)}) ${expr(ops[1], ctx)} else ${expr(ops[2], ctx)}"
        }
        val inner = ctx.copy(indent = ctx.indent + "    ")
        val sb = StringBuilder("when {\n")
        var i = 0
        while (i + 1 < ops.size) {
            sb.append("${inner.indent}${condition(ops[i], inner)} -> ${expr(ops[i + 1], inner)}\n")
            i += 2
        }
        sb.append("${inner.indent}else -> ${expr(ops.last(), inner)}\n")
        sb.append("${ctx.indent}}")
        return sb.toString()
    }

    private fun cast(call: RexCall, ctx: RexContext): String {
        val target = call.type.sqlTypeName
        val op = call.operands[0]
        // Fold casts of numeric literals: CAST(500 AS DOUBLE) -> 500.0
        if (op is RexLiteral && !op.isNull && op.type.sqlTypeName in SqlTypeName.NUMERIC_TYPES) {
            val v = op.getValueAs(BigDecimal::class.java)!!
            when (target) {
                SqlTypeName.DOUBLE, SqlTypeName.FLOAT, SqlTypeName.REAL -> return v.toDouble().toString()
                SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER ->
                    if (v.scale() <= 0) return v.intValueExact().toString()
                SqlTypeName.BIGINT -> if (v.scale() <= 0) return "${v.longValueExact()}L"
                else -> {}
            }
        }
        val value = expr(op, ctx)
        return when (target) {
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER -> "asInt($value)"
            SqlTypeName.BIGINT -> "asLong($value)"
            SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE, SqlTypeName.DECIMAL -> "asDouble($value)"
            SqlTypeName.CHAR, SqlTypeName.VARCHAR -> "asString($value)"
            SqlTypeName.BOOLEAN -> "asBoolean($value)"
            SqlTypeName.DATE -> "asDate($value)"
            SqlTypeName.TIME -> "asTime($value)"
            SqlTypeName.TIMESTAMP -> "asTimestamp($value)"
            else -> throw UnsupportedOperationException("Unsupported CAST target type: $target")
        }
    }

    /**
     * Numeric helpers compute in Long/Double; narrow the result back to the
     * Kotlin type matching the SQL-derived type. Skipped when the helper is
     * statically Double-valued and the target is approximate ([doubleValued]).
     */
    private fun narrow(call: RexCall, expr: String, doubleValued: Boolean = false): String =
        when (call.type.sqlTypeName) {
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER -> "asInt($expr)"
            SqlTypeName.BIGINT -> "asLong($expr)"
            SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE ->
                if (doubleValued) expr else "asDouble($expr)"
            SqlTypeName.DECIMAL ->
                // Exact decimals are approximated with Double unless the value is integral.
                if (call.type.scale > 0) {
                    if (doubleValued) expr else "asDouble($expr)"
                } else {
                    "asLong($expr)"
                }
            else -> expr
        }
}

/**
 * Kotlin type for a Calcite-derived [org.apache.calcite.rel.type.RelDataType].
 * All generated row properties are nullable: outer joins, SQL NULL semantics
 * and the untyped helper layer make non-null guarantees impractical.
 */
internal fun kotlinType(type: org.apache.calcite.rel.type.RelDataType): String = when (type.sqlTypeName) {
    SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER -> "Int?"
    SqlTypeName.BIGINT -> "Long?"
    SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE -> "Double?"
    SqlTypeName.DECIMAL -> if (type.scale > 0) "Double?" else "Long?"
    SqlTypeName.CHAR, SqlTypeName.VARCHAR -> "String?"
    SqlTypeName.BOOLEAN -> "Boolean?"
    SqlTypeName.DATE -> "java.time.LocalDate?"
    SqlTypeName.TIME -> "java.time.LocalTime?"
    SqlTypeName.TIMESTAMP -> "java.time.LocalDateTime?"
    in SqlTypeName.YEAR_INTERVAL_TYPES -> "java.time.Period?"
    in SqlTypeName.DAY_INTERVAL_TYPES -> "java.time.Duration?"
    else -> "Any?"
}

/** Kotlin string literal with escaping (including `$` to keep templates inert). */
internal fun kotlinString(s: String): String = buildString {
    append('"')
    for (c in s) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}
