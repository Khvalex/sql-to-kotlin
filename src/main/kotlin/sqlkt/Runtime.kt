package sqlkt

/**
 * Runtime prelude appended verbatim to every generated file, so the generated
 * `.kt` is self-contained: it only needs kotlin-stdlib to compile and run.
 *
 * The helpers implement SQL semantics on top of untyped rows (`List<Any?>`):
 * three-valued logic, null-propagating comparisons/arithmetic, joins and
 * aggregate functions.
 */
internal val RUNTIME_PRELUDE = """
// ---------------------------------------------------------------------------
// Runtime prelude (generated together with the query; SQL semantics helpers).
// ---------------------------------------------------------------------------

/** SQL WHERE semantics: only TRUE passes, FALSE and NULL are filtered out. */
private fun truth(b: Any?): Boolean = b == true

/** Null-propagating comparison with numeric type coercion (Int vs Long vs Double). */
private fun cmp(a: Any?, b: Any?): Int? {
    if (a == null || b == null) return null
    if (a is Number && b is Number) {
        return if (a is Double || b is Double || a is Float || b is Float) {
            a.toDouble().compareTo(b.toDouble())
        } else {
            a.toLong().compareTo(b.toLong())
        }
    }
    @Suppress("UNCHECKED_CAST")
    return (a as Comparable<Any?>).compareTo(b)
}

private fun eq(a: Any?, b: Any?): Boolean? = cmp(a, b)?.let { it == 0 }
private fun neq(a: Any?, b: Any?): Boolean? = cmp(a, b)?.let { it != 0 }
private fun lt(a: Any?, b: Any?): Boolean? = cmp(a, b)?.let { it < 0 }
private fun lte(a: Any?, b: Any?): Boolean? = cmp(a, b)?.let { it <= 0 }
private fun gt(a: Any?, b: Any?): Boolean? = cmp(a, b)?.let { it > 0 }
private fun gte(a: Any?, b: Any?): Boolean? = cmp(a, b)?.let { it >= 0 }

/** NULL-safe equality: `IS NOT DISTINCT FROM`. */
private fun isNotDistinct(a: Any?, b: Any?): Boolean =
    if (a == null || b == null) a == null && b == null else eq(a, b) == true

private fun isDistinct(a: Any?, b: Any?): Boolean = !isNotDistinct(a, b)

// Three-valued logic.
private fun and3(a: Any?, b: Any?): Boolean? {
    val x = a as Boolean?
    val y = b as Boolean?
    return when {
        x == false || y == false -> false
        x == null || y == null -> null
        else -> true
    }
}

private fun or3(a: Any?, b: Any?): Boolean? {
    val x = a as Boolean?
    val y = b as Boolean?
    return when {
        x == true || y == true -> true
        x == null || y == null -> null
        else -> false
    }
}

private fun not3(a: Any?): Boolean? = (a as Boolean?)?.let { !it }

// Null-propagating arithmetic. Computes in Long for integral operands and in
// Double when either side is floating point; the generated code narrows the
// result to the SQL-derived type via asInt/asLong/asDouble.
private fun numBin(a: Any?, b: Any?, longOp: (Long, Long) -> Long, doubleOp: (Double, Double) -> Double): Number? {
    if (a == null || b == null) return null
    a as Number
    b as Number
    return if (a is Double || b is Double || a is Float || b is Float) {
        doubleOp(a.toDouble(), b.toDouble())
    } else {
        longOp(a.toLong(), b.toLong())
    }
}

private fun numAdd(a: Any?, b: Any?): Number? = numBin(a, b, { x, y -> x + y }, { x, y -> x + y })
private fun numSub(a: Any?, b: Any?): Number? = numBin(a, b, { x, y -> x - y }, { x, y -> x - y })
private fun numMul(a: Any?, b: Any?): Number? = numBin(a, b, { x, y -> x * y }, { x, y -> x * y })
private fun numDiv(a: Any?, b: Any?): Number? = numBin(a, b, { x, y -> x / y }, { x, y -> x / y })
private fun numMod(a: Any?, b: Any?): Number? = numBin(a, b, { x, y -> x % y }, { x, y -> x % y })
private fun numNeg(a: Any?): Number? = numSub(0, a)

// Narrowing to the SQL-derived result type.
private fun asInt(x: Any?): Int? = (x as Number?)?.toInt()
private fun asLong(x: Any?): Long? = (x as Number?)?.toLong()
private fun asDouble(x: Any?): Double? = (x as Number?)?.toDouble()
private fun asString(x: Any?): String? = x?.toString()
private fun asBoolean(x: Any?): Boolean? = x as Boolean?

// String functions.
private fun upper(s: Any?): String? = (s as String?)?.uppercase()
private fun lower(s: Any?): String? = (s as String?)?.lowercase()
private fun charLength(s: Any?): Int? = (s as String?)?.length
private fun concatStr(a: Any?, b: Any?): String? =
    if (a == null || b == null) null else a.toString() + b.toString()

/** SQL SUBSTRING: 1-based `from`, optional length. */
private fun substr(s: Any?, from: Any?, len: Any? = null): String? {
    if (s == null || from == null) return null
    s as String
    val start = ((from as Number).toInt() - 1).coerceAtLeast(0)
    if (start >= s.length) return ""
    val end = if (len == null) s.length else (start + (len as Number).toInt()).coerceIn(start, s.length)
    return s.substring(start, end)
}

private fun sqlAbs(a: Any?): Number? {
    if (a == null) return null
    return when (a) {
        is Double -> kotlin.math.abs(a)
        is Float -> kotlin.math.abs(a.toDouble())
        is Long -> kotlin.math.abs(a)
        else -> kotlin.math.abs((a as Number).toInt())
    }
}

private fun coalesce(vararg values: Any?): Any? = values.firstOrNull { it != null }

/** SQL LIKE with % and _ wildcards. */
private fun like(s: Any?, pattern: Any?): Boolean? {
    if (s == null || pattern == null) return null
    val regex = StringBuilder()
    for (ch in pattern as String) {
        when (ch) {
            '%' -> regex.append(".*")
            '_' -> regex.append('.')
            else -> regex.append(Regex.escape(ch.toString()))
        }
    }
    return Regex(regex.toString(), RegexOption.DOT_MATCHES_ALL).matches(s as String)
}

// Joins. A row of the joined relation is the concatenation of a left row and a
// right row; the condition sees that concatenated row (matching Calcite's
// convention for join conditions).
private enum class JoinType { INNER, LEFT, RIGHT, FULL, SEMI, ANTI }

private fun joinRows(
    left: List<List<Any?>>,
    right: List<List<Any?>>,
    leftArity: Int,
    rightArity: Int,
    type: JoinType,
    cond: (List<Any?>) -> Boolean,
): List<List<Any?>> {
    val result = mutableListOf<List<Any?>>()
    when (type) {
        JoinType.SEMI -> left.filterTo(result) { l -> right.any { r -> cond(l + r) } }
        JoinType.ANTI -> left.filterTo(result) { l -> right.none { r -> cond(l + r) } }
        JoinType.INNER, JoinType.LEFT -> {
            for (l in left) {
                var matched = false
                for (r in right) {
                    val row = l + r
                    if (cond(row)) {
                        matched = true
                        result += row
                    }
                }
                if (!matched && type == JoinType.LEFT) result += l + arrayOfNulls<Any?>(rightArity)
            }
        }
        JoinType.RIGHT -> {
            for (r in right) {
                var matched = false
                for (l in left) {
                    val row = l + r
                    if (cond(row)) {
                        matched = true
                        result += row
                    }
                }
                if (!matched) result += List<Any?>(leftArity) { null } + r
            }
        }
        JoinType.FULL -> {
            val rightMatched = BooleanArray(right.size)
            for (l in left) {
                var matched = false
                for ((ri, r) in right.withIndex()) {
                    val row = l + r
                    if (cond(row)) {
                        matched = true
                        rightMatched[ri] = true
                        result += row
                    }
                }
                if (!matched) result += l + arrayOfNulls<Any?>(rightArity)
            }
            for ((ri, r) in right.withIndex()) {
                if (!rightMatched[ri]) result += List<Any?>(leftArity) { null } + r
            }
        }
    }
    return result
}

// Aggregate functions. SQL semantics: nulls are ignored; SUM/AVG/MIN/MAX over
// an empty (or all-null) set yield NULL, COUNT yields 0.
private fun aggCount(values: List<Any?>): Long = values.count { it != null }.toLong()

private fun aggSum(values: List<Any?>): Number? {
    val nonNull = values.filterIsInstance<Number>()
    if (nonNull.isEmpty()) return null
    return if (nonNull.any { it is Double || it is Float }) {
        nonNull.sumOf { it.toDouble() }
    } else {
        nonNull.sumOf { it.toLong() }
    }
}

private fun aggSum0(values: List<Any?>): Number = aggSum(values) ?: 0L

private fun aggAvg(values: List<Any?>): Double? {
    val nonNull = values.filterIsInstance<Number>()
    if (nonNull.isEmpty()) return null
    return nonNull.sumOf { it.toDouble() } / nonNull.size
}

private fun aggMin(values: List<Any?>): Any? =
    values.filterNotNull().minWithOrNull { a, b -> cmp(a, b)!! }

private fun aggMax(values: List<Any?>): Any? =
    values.filterNotNull().maxWithOrNull { a, b -> cmp(a, b)!! }

/** Scalar sub-query result: at most one row is allowed. */
private fun aggSingleValue(values: List<Any?>): Any? {
    require(values.size <= 1) { "Scalar sub-query returned more than one row" }
    return values.firstOrNull()
}

// ORDER BY.
private class SortKey(val index: Int, val asc: Boolean, val nullsFirst: Boolean)

private fun orderCmp(a: Any?, b: Any?, asc: Boolean, nullsFirst: Boolean): Int {
    if (a == null && b == null) return 0
    if (a == null) return if (nullsFirst) -1 else 1
    if (b == null) return if (nullsFirst) 1 else -1
    val c = cmp(a, b)!!
    return if (asc) c else -c
}

private fun rowComparator(keys: List<SortKey>): Comparator<List<Any?>> = Comparator { a, b ->
    for (k in keys) {
        val c = orderCmp(a[k.index], b[k.index], k.asc, k.nullsFirst)
        if (c != 0) return@Comparator c
    }
    0
}
"""
