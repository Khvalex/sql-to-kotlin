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

private fun <T : Any> coalesce(vararg values: T?): T? = values.firstOrNull { it != null }

// Math functions. Transcendentals compute in Double; the generated code narrows
// results back to the SQL-derived type via asInt/asLong/asDouble.
private fun math1(x: Any?, f: (Double) -> Double): Double? = (x as Number?)?.let { f(it.toDouble()) }

private fun numPower(a: Any?, b: Any?): Double? =
    if (a == null || b == null) null else Math.pow((a as Number).toDouble(), (b as Number).toDouble())

private fun numSqrt(x: Any?): Double? = math1(x) { kotlin.math.sqrt(it) }
private fun numExp(x: Any?): Double? = math1(x) { kotlin.math.exp(it) }
private fun numLn(x: Any?): Double? = math1(x) { kotlin.math.ln(it) }
private fun numLog10(x: Any?): Double? = math1(x) { kotlin.math.log10(it) }
private fun numSin(x: Any?): Double? = math1(x) { kotlin.math.sin(it) }
private fun numCos(x: Any?): Double? = math1(x) { kotlin.math.cos(it) }
private fun numTan(x: Any?): Double? = math1(x) { kotlin.math.tan(it) }
private fun numCot(x: Any?): Double? = math1(x) { 1.0 / kotlin.math.tan(it) }
private fun numAsin(x: Any?): Double? = math1(x) { kotlin.math.asin(it) }
private fun numAcos(x: Any?): Double? = math1(x) { kotlin.math.acos(it) }
private fun numAtan(x: Any?): Double? = math1(x) { kotlin.math.atan(it) }
private fun numDegrees(x: Any?): Double? = math1(x) { Math.toDegrees(it) }
private fun numRadians(x: Any?): Double? = math1(x) { Math.toRadians(it) }

private fun numAtan2(a: Any?, b: Any?): Double? =
    if (a == null || b == null) null
    else kotlin.math.atan2((a as Number).toDouble(), (b as Number).toDouble())

private fun numSign(x: Any?): Int? = (x as Number?)?.toDouble()?.let { kotlin.math.sign(it).toInt() }

/** FLOOR/CEIL preserve integral values; floating point is floored/ceiled. */
private fun numFloor(x: Any?): Number? = when (x) {
    null -> null
    is Double -> kotlin.math.floor(x)
    is Float -> kotlin.math.floor(x.toDouble())
    else -> x as Number
}

private fun numCeil(x: Any?): Number? = when (x) {
    null -> null
    is Double -> kotlin.math.ceil(x)
    is Float -> kotlin.math.ceil(x.toDouble())
    else -> x as Number
}

/** SQL ROUND(x [, n]): half-up rounding to n decimal places (n may be negative). */
private fun numRound(x: Any?, n: Any? = null): Number? {
    if (x == null || (n == null && x !is Double && x !is Float)) return x as Number?
    val scale = (n as Number?)?.toInt() ?: 0
    return if (x is Double || x is Float) {
        java.math.BigDecimal.valueOf((x as Number).toDouble())
            .setScale(scale, java.math.RoundingMode.HALF_UP).toDouble()
    } else {
        java.math.BigDecimal.valueOf((x as Number).toLong())
            .setScale(scale, java.math.RoundingMode.HALF_UP).toLong()
    }
}

/** SQL TRUNCATE(x [, n]): rounding toward zero to n decimal places. */
private fun numTruncate(x: Any?, n: Any? = null): Number? {
    if (x == null || (n == null && x !is Double && x !is Float)) return x as Number?
    val scale = (n as Number?)?.toInt() ?: 0
    return if (x is Double || x is Float) {
        java.math.BigDecimal.valueOf((x as Number).toDouble())
            .setScale(scale, java.math.RoundingMode.DOWN).toDouble()
    } else {
        java.math.BigDecimal.valueOf((x as Number).toLong())
            .setScale(scale, java.math.RoundingMode.DOWN).toLong()
    }
}

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

// Date/time. SQL DATE -> LocalDate, TIME -> LocalTime, TIMESTAMP -> LocalDateTime;
// intervals: day-time -> Duration, year-month -> Period.
private fun asDate(x: Any?): java.time.LocalDate? = when (x) {
    null -> null
    is java.time.LocalDate -> x
    is java.time.LocalDateTime -> x.toLocalDate()
    is String -> java.time.LocalDate.parse(x.trim())
    else -> throw IllegalArgumentException("Cannot cast value to DATE: " + x)
}

private fun asTime(x: Any?): java.time.LocalTime? = when (x) {
    null -> null
    is java.time.LocalTime -> x
    is java.time.LocalDateTime -> x.toLocalTime()
    is String -> java.time.LocalTime.parse(x.trim())
    else -> throw IllegalArgumentException("Cannot cast value to TIME: " + x)
}

private fun asTimestamp(x: Any?): java.time.LocalDateTime? = when (x) {
    null -> null
    is java.time.LocalDateTime -> x
    is java.time.LocalDate -> x.atStartOfDay()
    is String -> java.time.LocalDateTime.parse(x.trim().replace(' ', 'T'))
    else -> throw IllegalArgumentException("Cannot cast value to TIMESTAMP: " + x)
}

/** `datetime + interval` (either operand order). */
private fun dtPlus(a: Any?, b: Any?): Any? {
    if (a == null || b == null) return null
    val (dt, iv) = if (a is java.time.temporal.Temporal) a to b else b to a
    return when (dt) {
        is java.time.LocalDate -> when (iv) {
            is java.time.Duration -> dt.plusDays(iv.toDays())
            is java.time.Period -> dt.plus(iv)
            else -> throw IllegalArgumentException("Cannot add to DATE: " + iv)
        }
        is java.time.LocalDateTime -> when (iv) {
            is java.time.Duration -> dt.plus(iv)
            is java.time.Period -> dt.plus(iv)
            else -> throw IllegalArgumentException("Cannot add to TIMESTAMP: " + iv)
        }
        is java.time.LocalTime -> when (iv) {
            is java.time.Duration -> dt.plus(iv)
            else -> throw IllegalArgumentException("Cannot add to TIME: " + iv)
        }
        else -> throw IllegalArgumentException("Cannot add interval to: " + dt)
    }
}

private fun dtMinus(a: Any?, b: Any?): Any? = when (b) {
    null -> null
    is java.time.Duration -> dtPlus(a, b.negated())
    is java.time.Period -> dtPlus(a, b.negated())
    else -> throw IllegalArgumentException("Cannot subtract from a datetime: " + b)
}

private fun toDateTime(x: Any?): java.time.LocalDateTime = when (x) {
    is java.time.LocalDateTime -> x
    is java.time.LocalDate -> x.atStartOfDay()
    else -> throw IllegalArgumentException("Not a datetime value: " + x)
}

/** `datetime - datetime` with a day-time interval result. */
private fun dtDiffDuration(a: Any?, b: Any?): java.time.Duration? =
    if (a == null || b == null) null else java.time.Duration.between(toDateTime(b), toDateTime(a))

/** `datetime - datetime` with a year-month interval result. */
private fun dtDiffMonths(a: Any?, b: Any?): java.time.Period =
    java.time.Period.ofMonths(java.time.temporal.ChronoUnit.MONTHS.between(toDateTime(b), toDateTime(a)).toInt())

/** EXTRACT(unit FROM datetime); returns BIGINT (Long) per Calcite's typing. */
private fun extractField(unit: String, x: Any?): Long? {
    if (x == null) return null
    val date = when (x) {
        is java.time.LocalDate -> x
        is java.time.LocalDateTime -> x.toLocalDate()
        else -> null
    }
    val time = when (x) {
        is java.time.LocalTime -> x
        is java.time.LocalDateTime -> x.toLocalTime()
        else -> null
    }
    return when (unit) {
        "YEAR" -> date?.year?.toLong()
        "QUARTER" -> date?.let { ((it.monthValue - 1) / 3 + 1).toLong() }
        "MONTH" -> date?.monthValue?.toLong()
        "DAY" -> date?.dayOfMonth?.toLong()
        "DOY" -> date?.dayOfYear?.toLong()
        "DOW" -> date?.dayOfWeek?.value?.let { (it % 7 + 1).toLong() } // SQL: Sunday = 1
        "WEEK" -> date?.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())?.toLong()
        "HOUR" -> time?.hour?.toLong()
        "MINUTE" -> time?.minute?.toLong()
        "SECOND" -> time?.second?.toLong()
        else -> throw UnsupportedOperationException("EXTRACT unit not supported: " + unit)
    }
}

// Joins: typed variants. The combiner receives null for the missing side of
// outer joins; the condition only ever sees actual candidate pairs.
private fun <L, R, T> innerJoin(left: List<L>, right: List<R>, on: (L, R) -> Boolean, combine: (L, R) -> T): List<T> {
    val result = mutableListOf<T>()
    for (l in left) for (r in right) if (on(l, r)) result += combine(l, r)
    return result
}

private fun <L, R, T> leftJoin(left: List<L>, right: List<R>, on: (L, R) -> Boolean, combine: (L, R?) -> T): List<T> {
    val result = mutableListOf<T>()
    for (l in left) {
        var matched = false
        for (r in right) if (on(l, r)) {
            matched = true
            result += combine(l, r)
        }
        if (!matched) result += combine(l, null)
    }
    return result
}

private fun <L, R, T> rightJoin(left: List<L>, right: List<R>, on: (L, R) -> Boolean, combine: (L?, R) -> T): List<T> {
    val result = mutableListOf<T>()
    for (r in right) {
        var matched = false
        for (l in left) if (on(l, r)) {
            matched = true
            result += combine(l, r)
        }
        if (!matched) result += combine(null, r)
    }
    return result
}

private fun <L, R, T> fullJoin(left: List<L>, right: List<R>, on: (L, R) -> Boolean, combine: (L?, R?) -> T): List<T> {
    val result = mutableListOf<T>()
    val rightMatched = BooleanArray(right.size)
    for (l in left) {
        var matched = false
        for ((ri, r) in right.withIndex()) if (on(l, r)) {
            matched = true
            rightMatched[ri] = true
            result += combine(l, r)
        }
        if (!matched) result += combine(l, null)
    }
    for ((ri, r) in right.withIndex()) if (!rightMatched[ri]) result += combine(null, r)
    return result
}

private fun <L, R> semiJoin(left: List<L>, right: List<R>, on: (L, R) -> Boolean): List<L> =
    left.filter { l -> right.any { r -> on(l, r) } }

private fun <L, R> antiJoin(left: List<L>, right: List<R>, on: (L, R) -> Boolean): List<L> =
    left.filter { l -> right.none { r -> on(l, r) } }

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

private fun <T : Any> aggMin(values: List<T?>): T? =
    values.filterNotNull().minWithOrNull { a, b -> cmp(a, b)!! }

private fun <T : Any> aggMax(values: List<T?>): T? =
    values.filterNotNull().maxWithOrNull { a, b -> cmp(a, b)!! }

/** Scalar sub-query result: at most one row is allowed. */
private fun <T> aggSingleValue(values: List<T>): T? {
    require(values.size <= 1) { "Scalar sub-query returned more than one row" }
    return values.firstOrNull()
}

// ORDER BY.
private fun orderCmp(a: Any?, b: Any?, asc: Boolean, nullsFirst: Boolean): Int {
    if (a == null && b == null) return 0
    if (a == null) return if (nullsFirst) -1 else 1
    if (b == null) return if (nullsFirst) 1 else -1
    val c = cmp(a, b)!!
    return if (asc) c else -c
}

private fun <T> orderBy(selector: (T) -> Any?, asc: Boolean, nullsFirst: Boolean): Comparator<T> =
    Comparator { a, b -> orderCmp(selector(a), selector(b), asc, nullsFirst) }

private fun <T> Comparator<T>.thenOrderBy(selector: (T) -> Any?, asc: Boolean, nullsFirst: Boolean): Comparator<T> =
    thenComparing(orderBy(selector, asc, nullsFirst))
"""
