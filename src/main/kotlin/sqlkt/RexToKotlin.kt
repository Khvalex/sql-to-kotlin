package sqlkt

import org.apache.calcite.avatica.util.TimeUnitRange
import org.apache.calcite.rel.type.RelDataType
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
import java.math.BigDecimal

/**
 * Translation context for one generated lambda.
 *
 * @param bindings input ref index -> accessor (`row.salary`, `l.deptno`)
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
 * Invariant: the static Kotlin type of the emitted expression matches
 * [kotlinType] of the node's Calcite-derived type, nullability included.
 * Non-null operands therefore get native Kotlin operators (`a > b`, `a + b`,
 * `s.uppercase()`); nullable ones go through the SQL-semantics helpers from
 * the runtime prelude (`gt`, `addD`, ...), and the rare spots where a helper
 * is statically nullable but Calcite derives NOT NULL are coerced with `!!`.
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

    // ------------------------------------------------------------ emission

    private fun nn(node: RexNode): Boolean = !node.type.isNullable

    /** Kotlin expression for an already [expand]ed node; see the class invariant. */
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

    /**
     * Kotlin `Boolean` (non-null) expression, for filter/join conditions and
     * `when` branches. In this context only TRUE passes, which lets AND/OR
     * decompose into `&&` / `||` without losing SQL 3-valued semantics, and
     * `a = b` become native `==` when at least one side is non-null.
     */
    fun condition(node: RexNode, ctx: RexContext): String {
        if (node.toString() in ctx.shared) {
            val e = expr(node, ctx)
            return if (nn(node)) e else "truth($e)"
        }
        return when (node.kind) {
            SqlKind.AND -> (node as RexCall).operands.joinToString(" && ") { condition(it, ctx) }
            SqlKind.OR -> "(" + (node as RexCall).operands.joinToString(" || ") { condition(it, ctx) } + ")"
            SqlKind.NOT -> {
                val op = (node as RexCall).operands[0]
                if (nn(op)) "!${parenthesize(expr(op, ctx))}" else "${expr(op, ctx)} == false"
            }
            SqlKind.EQUALS -> {
                // NULL = x is never TRUE, and Kotlin `null == x` is false when
                // x is non-null — so one non-null side is enough for native ==.
                val (a, b) = (node as RexCall).operands
                if ((nn(a) || nn(b)) && sameBaseType(a.type, b.type)) {
                    "${expr(a, ctx)} == ${expr(b, ctx)}"
                } else {
                    "truth(eq(${expr(a, ctx)}, ${expr(b, ctx)}))"
                }
            }
            else -> {
                val e = expr(node, ctx)
                if (staticBoolean(node)) stripOuterParens(e) else "truth($e)"
            }
        }
    }

    /** True when [expr] for this node is statically a non-null Kotlin Boolean. */
    private fun staticBoolean(node: RexNode): Boolean = when (node.kind) {
        SqlKind.IS_NULL, SqlKind.IS_NOT_NULL,
        SqlKind.IS_TRUE, SqlKind.IS_NOT_TRUE,
        SqlKind.IS_FALSE, SqlKind.IS_NOT_FALSE,
        SqlKind.IS_DISTINCT_FROM, SqlKind.IS_NOT_DISTINCT_FROM,
        -> true

        else -> nn(node) && node.type.sqlTypeName == SqlTypeName.BOOLEAN
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

    /** Wraps in parens unless the expression is a simple accessor or call. */
    private fun parenthesize(e: String): String =
        if (e.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*(\\(.*\\))?")) && !e.contains(' ')) e else "($e)"

    /** Coerces a statically nullable emission when Calcite derives NOT NULL. */
    private fun nnWrap(type: RelDataType, text: String): String =
        if (!type.isNullable) "$text!!" else text

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
                "java.time.LocalDate.parse(${kotlinString(lit.getValueAs(DateStringClass).toString())})"
            SqlTypeName.TIME ->
                "java.time.LocalTime.parse(${kotlinString(lit.getValueAs(TimeStringClass).toString())})"
            SqlTypeName.TIMESTAMP ->
                "java.time.LocalDateTime.parse(" +
                    kotlinString(lit.getValueAs(TimestampStringClass).toString().replace(' ', 'T')) + ")"
            in SqlTypeName.YEAR_INTERVAL_TYPES ->
                "java.time.Period.ofMonths(${lit.getValueAs(BigDecimal::class.java)!!.intValueExact()})"
            in SqlTypeName.DAY_INTERVAL_TYPES ->
                "java.time.Duration.ofMillis(${lit.getValueAs(BigDecimal::class.java)!!.longValueExact()}L)"
            else -> throw UnsupportedOperationException("Unsupported literal type: ${lit.type.sqlTypeName}")
        }
    }

    private fun isDatetime(type: SqlTypeName): Boolean =
        type == SqlTypeName.DATE || type == SqlTypeName.TIME ||
            type == SqlTypeName.TIMESTAMP || type == SqlTypeName.TIMESTAMP_WITH_LOCAL_TIME_ZONE

    private fun sameBaseType(a: RelDataType, b: RelDataType): Boolean {
        val ba = kotlinBaseType(a)
        return ba != null && ba == kotlinBaseType(b)
    }

    private fun isNumeric(type: RelDataType): Boolean = numericFamily(type) != null

    private fun numericFamily(type: RelDataType): Char? = when (type.sqlTypeName) {
        SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER -> 'I'
        SqlTypeName.BIGINT -> 'L'
        SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE -> 'D'
        SqlTypeName.DECIMAL -> if (type.scale > 0) 'D' else 'L'
        else -> null
    }

    private fun call(call: RexCall, ctx: RexContext): String {
        fun arg(i: Int) = expr(call.operands[i], ctx)
        fun helper(name: String) = "$name(${call.operands.joinToString(", ") { expr(it, ctx) }})"

        return when (call.kind) {
            SqlKind.AND, SqlKind.OR -> {
                val (native, helper3) = if (call.kind == SqlKind.AND) "&&" to "and3" else "||" to "or3"
                if (call.operands.all { nn(it) }) {
                    "(" + call.operands.joinToString(" $native ") { expr(it, ctx) } + ")"
                } else {
                    call.operands.map { expr(it, ctx) }.reduce { a, b -> "$helper3($a, $b)" }
                }
            }
            SqlKind.NOT ->
                if (nn(call.operands[0])) "!${parenthesize(arg(0))}" else "not3(${arg(0)})"

            SqlKind.EQUALS -> comparison(call, "==", "eq", ctx)
            SqlKind.NOT_EQUALS -> comparison(call, "!=", "neq", ctx)
            SqlKind.LESS_THAN -> comparison(call, "<", "lt", ctx)
            SqlKind.LESS_THAN_OR_EQUAL -> comparison(call, "<=", "lte", ctx)
            SqlKind.GREATER_THAN -> comparison(call, ">", "gt", ctx)
            SqlKind.GREATER_THAN_OR_EQUAL -> comparison(call, ">=", "gte", ctx)
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
                else arith(call, "+", "add", "numAdd", ctx)
            SqlKind.MINUS -> when {
                isDatetime(call.type.sqlTypeName) -> "(${helper("dtMinus")} as ${kotlinType(call.type)})"
                // `datetime - datetime` (MINUS_DATE operator, kind MINUS) yields an interval.
                call.type.sqlTypeName in SqlTypeName.YEAR_INTERVAL_TYPES ->
                    nnWrap(call.type, "dtDiffMonths(${arg(0)}, ${arg(1)})")
                call.type.sqlTypeName in SqlTypeName.DAY_INTERVAL_TYPES ->
                    nnWrap(call.type, "dtDiffDuration(${arg(0)}, ${arg(1)})")
                else -> arith(call, "-", "sub", "numSub", ctx)
            }
            SqlKind.TIMES -> arith(call, "*", "mul", "numMul", ctx)
            SqlKind.DIVIDE -> arith(call, "/", "div", "numDiv", ctx)
            SqlKind.MOD -> arith(call, "%", "mod", "numMod", ctx)
            SqlKind.MINUS_PREFIX ->
                if (nn(call.operands[0]) && isNumeric(call.operands[0].type)) "(-${arg(0)})"
                else arith(call, null, "neg", "numNeg", ctx)
            SqlKind.PLUS_PREFIX -> arg(0)

            SqlKind.EXTRACT -> {
                val unit = (call.operands[0] as RexLiteral).getValueAs(TimeUnitRange::class.java)!!.name
                nnWrap(call.type, "extractField(${kotlinString(unit)}, ${arg(1)})")
            }

            SqlKind.FLOOR, SqlKind.CEIL -> {
                // Two-operand form is FLOOR(datetime TO unit) — not supported.
                require(call.operands.size == 1) { "FLOOR/CEIL with a time unit is not supported" }
                val op = call.operands[0]
                if (nn(op) && numericFamily(op.type) == 'D' && numericFamily(call.type) == 'D') {
                    val fn = if (call.kind == SqlKind.FLOOR) "floor" else "ceil"
                    "kotlin.math.$fn(${arg(0)})"
                } else {
                    val fn = if (call.kind == SqlKind.FLOOR) "numFloor" else "numCeil"
                    nnWrap(call.type, narrow(call, "$fn(${arg(0)})"))
                }
            }

            SqlKind.CASE -> caseWhen(call, ctx)
            SqlKind.CAST -> cast(call, ctx)

            SqlKind.LIKE -> nnWrap(call.type, helper("like"))

            else -> function(call, ctx)
        }
    }

    /** Binary comparison: native operator for non-null compatible operands. */
    private fun comparison(call: RexCall, op: String, helperName: String, ctx: RexContext): String {
        val (a, b) = call.operands
        val ordered = op !in setOf("==", "!=")
        val native = nn(a) && nn(b) &&
            if (ordered) {
                // Kotlin allows mixed-numeric `<`/`>`, otherwise require one type.
                (isNumeric(a.type) && isNumeric(b.type)) || sameBaseType(a.type, b.type)
            } else {
                // ==/!= must compare identical types (Int == Long is always false).
                sameBaseType(a.type, b.type)
            }
        return if (native) {
            "(${expr(a, ctx)} $op ${expr(b, ctx)})"
        } else {
            // The helper is Boolean?-typed; with non-null operands (e.g. Int
            // vs Long, where native == would be always-false) coerce to match
            // Calcite's NOT NULL derivation.
            nnWrap(call.type, "$helperName(${expr(a, ctx)}, ${expr(b, ctx)})")
        }
    }

    /**
     * Arithmetic. All operands non-null -> native Kotlin operators (numeric
     * promotion matches SQL's). One family, some nullable -> typed helper
     * (`subD`). Mixed families with nulls -> generic Number helper + narrowing.
     */
    private fun arith(call: RexCall, sym: String?, typedName: String, genericName: String, ctx: RexContext): String {
        val ops = call.operands
        if (ops.all { nn(it) && isNumeric(it.type) } && numericFamily(call.type) != null) {
            return if (sym == null) "(-${expr(ops[0], ctx)})"
            else "(" + ops.joinToString(" $sym ") { expr(it, ctx) } + ")"
        }
        val family = numericFamily(call.type)
        val args = ops.joinToString(", ") { expr(it, ctx) }
        if (family != null && ops.all { numericFamily(it.type) == family }) {
            return "$typedName$family($args)"
        }
        return nnWrap(call.type, narrow(call, "$genericName($args)"))
    }

    /** Named functions and operators without a dedicated SqlKind. */
    private fun function(call: RexCall, ctx: RexContext): String {
        fun helper(name: String) = "$name(${call.operands.joinToString(", ") { expr(it, ctx) }})"
        fun mathNative(fn: String): String {
            val op = call.operands[0]
            return if (nn(op) && numericFamily(op.type) == 'D') "kotlin.math.$fn(${expr(op, ctx)})"
            else nnWrap(call.type, helper("num${fn.replaceFirstChar { it.uppercase() }}"))
        }

        /** String method call: native `.method()` with `?.` for nullable receivers. */
        fun strMethod(method: String): String {
            val op = call.operands[0]
            val dot = if (nn(op)) "." else "?."
            return "${parenthesize(expr(op, ctx))}$dot$method"
        }

        return when (call.operator.name.uppercase()) {
            "||", "CONCAT" -> {
                val (a, b) = call.operands
                if (nn(a) && nn(b)) "(${expr(a, ctx)} + ${expr(b, ctx)})" else helper("concatStr")
            }
            "UPPER" -> strMethod("uppercase()")
            "LOWER" -> strMethod("lowercase()")
            "CHAR_LENGTH", "CHARACTER_LENGTH", "LENGTH" -> strMethod("length")
            "SUBSTRING" -> nnWrap(call.type, helper("substr"))
            "ABS" -> {
                val op = call.operands[0]
                if (nn(op) && isNumeric(op.type)) "kotlin.math.abs(${expr(op, ctx)})"
                else nnWrap(call.type, narrow(call, helper("sqlAbs")))
            }
            "MOD" -> arith(call, "%", "mod", "numMod", ctx)
            "POWER" -> {
                val (a, b) = call.operands
                if (nn(a) && nn(b) && isNumeric(a.type) && isNumeric(b.type)) {
                    "Math.pow(${toDouble(a, ctx)}, ${toDouble(b, ctx)})"
                } else {
                    helper("numPower")
                }
            }
            "SQRT" -> mathNative("sqrt")
            "EXP" -> mathNative("exp")
            "LN" -> mathNative("ln")
            "LOG10" -> mathNative("log10")
            "SIN" -> mathNative("sin")
            "COS" -> mathNative("cos")
            "TAN" -> mathNative("tan")
            "COT" -> nnWrap(call.type, helper("numCot"))
            "ASIN" -> mathNative("asin")
            "ACOS" -> mathNative("acos")
            "ATAN" -> mathNative("atan")
            "ATAN2" -> nnWrap(call.type, helper("numAtan2"))
            "DEGREES" -> nnWrap(call.type, helper("numDegrees"))
            "RADIANS" -> nnWrap(call.type, helper("numRadians"))
            "SIGN" -> nnWrap(call.type, narrow(call, helper("numSign")))
            "ROUND" -> nnWrap(call.type, narrow(call, helper("numRound")))
            "TRUNCATE" -> nnWrap(call.type, narrow(call, helper("numTruncate")))
            "PI" -> "kotlin.math.PI"
            "COALESCE" -> nnWrap(call.type, helper("coalesce")) // normally rewritten to CASE by Calcite
            "CURRENT_DATE" -> "java.time.LocalDate.now()"
            "CURRENT_TIMESTAMP", "LOCALTIMESTAMP" -> "java.time.LocalDateTime.now()"
            "CURRENT_TIME", "LOCALTIME" -> "java.time.LocalTime.now()"
            else -> throw UnsupportedOperationException("Unsupported SQL function/operator: ${call.operator.name}")
        }
    }

    private fun toDouble(node: RexNode, ctx: RexContext): String {
        val e = expr(node, ctx)
        return if (numericFamily(node.type) == 'D') e else "${parenthesize(e)}.toDouble()"
    }

    /**
     * CASE -> `if (c) a else b` for a single branch, multi-line `when` otherwise.
     * [RexContext.indent] is the absolute indentation of the expression.
     */
    private fun caseWhen(call: RexCall, ctx: RexContext): String {
        val ops = call.operands
        // Value operands: odd positions plus the trailing else.
        val values = (1 until ops.size step 2).map { ops[it] } + ops.last()
        val staticNullable = values.any { !nn(it) }

        val text = if (ops.size == 3) {
            "if (${condition(ops[0], ctx)}) ${expr(ops[1], ctx)} else ${expr(ops[2], ctx)}"
        } else {
            val inner = ctx.copy(indent = ctx.indent + "    ")
            buildString {
                append("when {\n")
                var i = 0
                while (i + 1 < ops.size) {
                    append("${inner.indent}${condition(ops[i], inner)} -> ${expr(ops[i + 1], inner)}\n")
                    i += 2
                }
                append("${inner.indent}else -> ${expr(ops.last(), inner)}\n")
                append("${ctx.indent}}")
            }
        }
        // Calcite can prove NOT NULL through IS NOT NULL guards that Kotlin
        // type inference cannot see; coerce in that case.
        return if (staticNullable && !call.type.isNullable) "($text)!!" else text
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
        val dot = if (nn(op)) "." else "?."
        val receiver = parenthesize(value)
        val converted = when (target) {
            SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER ->
                if (isNumeric(op.type)) "$receiver${dot}toInt()" else "asInt($value)"
            SqlTypeName.BIGINT ->
                if (isNumeric(op.type)) "$receiver${dot}toLong()" else "asLong($value)"
            SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE ->
                if (isNumeric(op.type)) "$receiver${dot}toDouble()" else "asDouble($value)"
            SqlTypeName.DECIMAL -> {
                val fn = if (call.type.scale > 0) "toDouble" else "toLong"
                if (isNumeric(op.type)) "$receiver$dot$fn()" else "as${fn.removePrefix("to")}($value)"
            }
            SqlTypeName.CHAR, SqlTypeName.VARCHAR -> "$receiver${dot}toString()"
            SqlTypeName.BOOLEAN -> value // BOOLEAN can only be cast from BOOLEAN here
            SqlTypeName.DATE -> "asDate($value)"
            SqlTypeName.TIME -> "asTime($value)"
            SqlTypeName.TIMESTAMP -> "asTimestamp($value)"
            else -> throw UnsupportedOperationException("Unsupported CAST target type: $target")
        }
        // The as*/native-?. paths are nullable; coerce when Calcite says NOT NULL.
        val staticNullable = !nn(op) || converted.startsWith("as")
        return if (staticNullable && !call.type.isNullable) "$converted!!" else converted
    }

    /**
     * Narrows a `Number?`-valued generic helper to the Kotlin type matching
     * the SQL-derived type (still nullable; pair with [nnWrap] when needed).
     */
    private fun narrow(call: RexCall, expr: String): String = when (numericFamily(call.type)) {
        'I' -> "asInt($expr)"
        'L' -> "asLong($expr)"
        'D' -> "asDouble($expr)"
        else -> expr
    }
}

/**
 * Kotlin type for a Calcite-derived [RelDataType], including nullability:
 * Calcite tracks it precisely (NOT NULL columns, outer-join sides, expression
 * derivation), and the generator preserves it so that non-null values get
 * native Kotlin operators.
 */
internal fun kotlinType(type: RelDataType): String {
    val base = kotlinBaseType(type) ?: return "Any?"
    return if (type.isNullable) "$base?" else base
}

/** Base Kotlin type without nullability; null for unmapped SQL types. */
internal fun kotlinBaseType(type: RelDataType): String? = when (type.sqlTypeName) {
    SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER -> "Int"
    SqlTypeName.BIGINT -> "Long"
    SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE -> "Double"
    SqlTypeName.DECIMAL -> if (type.scale > 0) "Double" else "Long"
    SqlTypeName.CHAR, SqlTypeName.VARCHAR -> "String"
    SqlTypeName.BOOLEAN -> "Boolean"
    SqlTypeName.DATE -> "java.time.LocalDate"
    SqlTypeName.TIME -> "java.time.LocalTime"
    SqlTypeName.TIMESTAMP -> "java.time.LocalDateTime"
    in SqlTypeName.YEAR_INTERVAL_TYPES -> "java.time.Period"
    in SqlTypeName.DAY_INTERVAL_TYPES -> "java.time.Duration"
    else -> null
}

private val DateStringClass = org.apache.calcite.util.DateString::class.java
private val TimeStringClass = org.apache.calcite.util.TimeString::class.java
private val TimestampStringClass = org.apache.calcite.util.TimestampString::class.java

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
