package sqlkt

import org.apache.calcite.rel.RelFieldCollation
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.core.Aggregate
import org.apache.calcite.rel.core.AggregateCall
import org.apache.calcite.rel.core.Filter
import org.apache.calcite.rel.core.Join
import org.apache.calcite.rel.core.JoinRelType
import org.apache.calcite.rel.core.Project
import org.apache.calcite.rel.core.Sort
import org.apache.calcite.rel.core.TableScan
import org.apache.calcite.rel.core.Union
import org.apache.calcite.rel.core.Values
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.type.SqlTypeName

private const val I1 = "    " // statement indent inside the function
private const val I2 = "        " // chained operator indent

/**
 * Walks a [RelNode] tree bottom-up and emits idiomatic-looking Kotlin:
 * linear operator runs become one fluent collection chain, joins/unions
 * materialize into named `val`s, columns get named locals inside lambdas
 * (`val salary = row[3]`), and subexpressions repeated within a projection
 * are extracted into a single local.
 */
class RelToKotlin(private val rex: RexToKotlin) {

    /** A fluent chain: head expression plus `.op(...)` segments (absolute-indented lines). */
    private class Chain(val head: String, val segments: MutableList<String> = mutableListOf())

    private val out = StringBuilder()
    private val usedNames = mutableMapOf<String, Int>()

    /** Tables scanned more than once are materialized into one shared `val`. */
    private val scanCounts = mutableMapOf<String, Int>()
    private val scanVars = mutableMapOf<String, String>()

    /** Emits the whole function body (statements + return). */
    fun generate(rel: RelNode, fieldNames: List<String>, functionName: String): String {
        countScans(rel)
        out.append("fun $functionName(tables: Map<String, List<Map<String, Any?>>>): List<Map<String, Any?>> {\n")
        val chain = visit(rel)
        chain.segments += mapToRowsSegment(fieldNames)
        out.append("${I1}return ${chain.head}\n")
        chain.segments.forEach { out.append(it) }
        out.append("}\n")
        return out.toString()
    }

    // ------------------------------------------------------------------ util

    private fun freshName(base: String): String {
        val n = usedNames.merge(base, 1, Int::plus)!!
        return if (n == 1) base else "$base$n"
    }

    /** Emits `val <name> = <chain>` unless the chain is already a bare variable. */
    private fun materialize(chain: Chain, base: String): String {
        if (chain.segments.isEmpty() && chain.head.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            return chain.head
        }
        val name = freshName(base)
        out.append("${I1}val $name = ${chain.head}\n")
        chain.segments.forEach { out.append(it) }
        return name
    }

    private val kotlinKeywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "val", "var", "when", "while",
        // names already used by the generated code and prelude
        "row", "key", "group", "tables", "it", "r", "truth",
    )

    /** SQL field name -> Kotlin local: DEV_PCT -> devPct, $f0 -> f0. */
    private fun localName(field: String, taken: MutableSet<String>): String {
        val parts = field.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        var name = parts.withIndex().joinToString("") { (i, p) ->
            if (i == 0) p.lowercase() else p.lowercase().replaceFirstChar { it.uppercase() }
        }.ifEmpty { "col" }
        if (name.first().isDigit()) name = "c$name"
        if (name in kotlinKeywords) name += "_"
        var unique = name
        var n = 2
        while (!taken.add(unique)) unique = "$name${n++}"
        return unique
    }

    /**
     * Local `val` declarations for a lambda: bindings for every referenced
     * column plus extracted common subexpressions.
     */
    private fun lambdaPrelude(
        exprs: List<RexNode>,
        inputFields: List<String>,
        outputFields: List<String?> = emptyList(),
        indent: String,
    ): Pair<List<String>, RexContext> {
        val taken = mutableSetOf<String>()
        val lines = mutableListOf<String>()

        val bindings = mutableMapOf<Int, String>()
        for (i in exprs.flatMap { rex.inputRefs(it) }.toSortedSet()) {
            val name = localName(inputFields.getOrElse(i) { "col$i" }, taken)
            bindings[i] = name
            lines += "${indent}val $name = row[$i]"
        }

        var shared = mapOf<String, String>()
        for (node in rex.sharedSubtrees(exprs)) {
            val digest = node.toString()
            // Prefer the projection alias when the shared subtree IS a projected column.
            val alias = exprs.indexOfFirst { it.toString() == digest }
                .let { outputFields.getOrNull(it) }
                ?.takeUnless { it.startsWith("EXPR$") || it.startsWith("\$f") }
            val name = localName(alias ?: "shared", taken)
            val ctx = RexContext(bindings, shared, indent)
            lines += "${indent}val $name = ${rex.expr(node, ctx)}"
            shared = shared + (digest to name)
        }
        return lines to RexContext(bindings, shared, indent)
    }

    // ------------------------------------------------------------- operators

    private fun visit(rel: RelNode): Chain = when (rel) {
        is TableScan -> tableScan(rel)
        is Filter -> visit(rel.input).also { it.segments += filterSegment(rel) }
        is Project -> visit(rel.input).also { it.segments += projectSegment(rel) }
        is Join -> join(rel)
        is Aggregate -> aggregate(rel)
        is Sort -> visit(rel.input).also { it.segments += sortSegments(rel) }
        is Values -> values(rel)
        is Union -> union(rel)
        else -> throw UnsupportedOperationException("Unsupported relational operator ${rel.relTypeName}: $rel")
    }

    private fun countScans(rel: RelNode) {
        if (rel is TableScan) scanCounts.merge(rel.table!!.qualifiedName.last(), 1, Int::plus)
        rel.inputs.forEach { countScans(it) }
    }

    private fun tableScan(rel: TableScan): Chain {
        val tableName = rel.table!!.qualifiedName.last()
        scanVars[tableName]?.let { return Chain(it) }
        val cells = rel.rowType.fieldNames.map { "r[${kotlinString(it)}]" }
        val oneLine = "$I2.map { r -> listOf<Any?>(${cells.joinToString(", ")}) }\n"
        val segment = if (oneLine.length <= 100) oneLine else buildString {
            append("$I2.map { r ->\n")
            append("$I2    listOf<Any?>(\n")
            cells.forEach { append("$I2        $it,\n") }
            append("$I2    )\n")
            append("$I2}\n")
        }
        val chain = Chain("tables.getValue(${kotlinString(tableName)})", mutableListOf(segment))
        // A table scanned twice becomes one shared `val` instead of two copies.
        if (scanCounts.getOrDefault(tableName, 0) > 1) {
            val name = materialize(chain, tableName.lowercase())
            scanVars[tableName] = name
            return Chain(name)
        }
        return chain
    }

    private fun filterSegment(rel: Filter): String {
        val cond = rex.expand(rel.condition)
        val (locals, ctx) = lambdaPrelude(listOf(cond), rel.input.rowType.fieldNames, indent = "$I2    ")
        return buildString {
            append("$I2.filter { row ->\n")
            locals.forEach { append("$it\n") }
            append("$I2    ${rex.condition(cond, ctx)}\n")
            append("$I2}\n")
        }
    }

    private fun projectSegment(rel: Project): String {
        val exprs = rel.projects.map { rex.expand(it) }
        val outputFields = rel.rowType.fieldNames
        val (locals, ctx) = lambdaPrelude(exprs, rel.input.rowType.fieldNames, outputFields, indent = "$I2    ")

        val cellCtx = ctx.copy(indent = "$I2        ")
        val cells = exprs.map { rex.expr(it, cellCtx) }
        return buildString {
            append("$I2.map { row ->\n")
            locals.forEach { append("$it\n") }
            append("$I2    listOf<Any?>(\n")
            cells.forEachIndexed { i, cell ->
                val alias = outputFields[i]
                val comment =
                    if (!alias.startsWith("EXPR$") && !alias.startsWith("\$f") && !cell.contains('\n') &&
                        !cell.equals(alias, ignoreCase = true)
                    ) " // $alias" else ""
                append("$I2        $cell,$comment\n")
            }
            append("$I2    )\n")
            append("$I2}\n")
        }
    }

    private fun join(rel: Join): Chain {
        val left = materialize(visit(rel.left), nameFor(rel.left))
        val right = materialize(visit(rel.right), nameFor(rel.right))
        val joinType = when (rel.joinType) {
            JoinRelType.INNER -> "INNER"
            JoinRelType.LEFT -> "LEFT"
            JoinRelType.RIGHT -> "RIGHT"
            JoinRelType.FULL -> "FULL"
            JoinRelType.SEMI -> "SEMI"
            JoinRelType.ANTI -> "ANTI"
            else -> throw UnsupportedOperationException("Unsupported join type: ${rel.joinType}")
        }
        // The condition sees left fields followed by right fields; uniquify names.
        val taken = mutableSetOf<String>()
        val condFields = (rel.left.rowType.fieldNames + rel.right.rowType.fieldNames)
        val leftArity = rel.left.rowType.fieldCount
        val rightArity = rel.right.rowType.fieldCount

        val cond = rex.expand(rel.condition)
        val bindings = mutableMapOf<Int, String>()
        val locals = mutableListOf<String>()
        for (i in rex.inputRefs(cond).sorted()) {
            val name = localName(condFields.getOrElse(i) { "col$i" }, taken)
            bindings[i] = name
            locals += "${I1}    val $name = row[$i]"
        }
        val ctx = RexContext(bindings, indent = "$I1    ")

        val name = freshName("joined")
        out.append("${I1}val $name = joinRows($left, $right, leftArity = $leftArity, rightArity = $rightArity, JoinType.$joinType) { row ->\n")
        locals.forEach { out.append("$it\n") }
        out.append("$I1    ${rex.condition(cond, ctx)}\n")
        out.append("$I1}\n")
        return Chain(name)
    }

    private fun aggregate(rel: Aggregate): Chain {
        require(rel.groupSets.size == 1) { "GROUPING SETS / ROLLUP / CUBE are not supported" }
        val inputFields = rel.input.rowType.fieldNames
        val keys = rel.groupSet.asList()

        // Global aggregate: exactly one output row, even for empty input.
        if (keys.isEmpty()) {
            val input = materialize(visit(rel.input), nameFor(rel.input))
            val name = freshName("aggregated")
            out.append("${I1}val $name = listOf(\n")
            out.append("$I1    listOf<Any?>(\n")
            rel.aggCallList.forEach { call ->
                out.append("$I1        ${aggExpr(call, input, inputFields)},${aggComment(call, inputFields)}\n")
            }
            out.append("$I1    ),\n")
            out.append("$I1)\n")
            return Chain(name)
        }

        val chain = visit(rel.input)

        // SELECT DISTINCT: group keys only, no aggregate calls.
        if (rel.aggCallList.isEmpty()) {
            val keyCells = keys.joinToString(", ") { "row[$it]" }
            chain.segments += "$I2.map { row -> listOf<Any?>($keyCells) } // ${keyComment(keys, inputFields)}\n"
            chain.segments += "$I2.distinct()\n"
            return chain
        }

        val keyCells = keys.joinToString(", ") { "row[$it]" }
        chain.segments += "$I2.groupBy { row -> listOf($keyCells) } // GROUP BY ${keyComment(keys, inputFields)}\n"
        chain.segments += buildString {
            append("$I2.map { (key, group) ->\n")
            append("$I2    key + listOf<Any?>(\n")
            rel.aggCallList.forEach { call ->
                append("$I2        ${aggExpr(call, "group", inputFields)},${aggComment(call, inputFields)}\n")
            }
            append("$I2    )\n")
            append("$I2}\n")
        }
        return chain
    }

    private fun keyComment(keys: List<Int>, fields: List<String>): String =
        keys.joinToString(", ") { fields.getOrElse(it) { "col$it" } }

    private fun aggComment(call: AggregateCall, fields: List<String>): String {
        val args = call.argList.joinToString(", ") { fields.getOrElse(it) { "col$it" } }
        val distinct = if (call.isDistinct) "DISTINCT " else ""
        return " // ${call.aggregation.name}($distinct${args.ifEmpty { "*" }})"
    }

    private fun aggExpr(call: AggregateCall, groupVar: String, fields: List<String>): String {
        require(!call.hasFilter()) { "FILTER clause on aggregates is not supported" }
        val args = call.argList
        var values = if (args.isEmpty()) "" else "$groupVar.map { it[${args.single()}] }"
        if (call.isDistinct && args.isNotEmpty()) values += ".distinct()"

        val expr = when (call.aggregation.kind) {
            SqlKind.COUNT -> if (args.isEmpty()) "$groupVar.size.toLong()" else "aggCount($values)"
            SqlKind.SUM -> "aggSum($values)"
            SqlKind.SUM0 -> "aggSum0($values)"
            SqlKind.AVG -> "aggAvg($values)"
            SqlKind.MIN -> "aggMin($values)"
            SqlKind.MAX -> "aggMax($values)"
            SqlKind.SINGLE_VALUE -> "aggSingleValue($values)"
            else -> throw UnsupportedOperationException("Unsupported aggregate function: ${call.aggregation.name}")
        }

        // COUNT is already Long; AVG is already Double. SUM computes in
        // Long/Double and narrows to the SQL-derived type.
        return when (call.aggregation.kind) {
            SqlKind.COUNT -> expr
            SqlKind.AVG -> when (call.type.sqlTypeName) {
                SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE -> expr
                else -> narrowTo(call.type.sqlTypeName, expr)
            }
            SqlKind.SUM, SqlKind.SUM0 -> narrowTo(call.type.sqlTypeName, expr)
            else -> expr
        }
    }

    private fun narrowTo(type: SqlTypeName, expr: String): String = when (type) {
        SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER -> "asInt($expr)"
        SqlTypeName.BIGINT -> "asLong($expr)"
        SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE, SqlTypeName.DECIMAL -> "asDouble($expr)"
        else -> expr
    }

    private fun sortSegments(rel: Sort): List<String> {
        val segments = mutableListOf<String>()
        val fields = rel.input.rowType.fieldNames

        val collations = rel.collation.fieldCollations
        if (collations.isNotEmpty()) {
            val keys = collations.map { fc ->
                val asc = !fc.direction.isDescending
                val nullsFirst = when (fc.nullDirection) {
                    RelFieldCollation.NullDirection.FIRST -> true
                    RelFieldCollation.NullDirection.LAST -> false
                    // Calcite's default: nulls sort as the largest value.
                    else -> fc.direction.isDescending
                }
                "SortKey(${fc.fieldIndex}, asc = $asc, nullsFirst = $nullsFirst)"
            }
            val comment = collations.joinToString(", ") { fc ->
                fields.getOrElse(fc.fieldIndex) { "col${fc.fieldIndex}" } +
                    if (fc.direction.isDescending) " DESC" else ""
            }
            segments += if (keys.size == 1) {
                "$I2.sortedWith(rowComparator(listOf(${keys.single()}))) // ORDER BY $comment\n"
            } else {
                buildString {
                    append("$I2.sortedWith(rowComparator(listOf( // ORDER BY $comment\n")
                    keys.forEach { append("$I2    $it,\n") }
                    append("$I2)))\n")
                }
            }
        }

        val offset = (rel.offset as RexLiteral?)?.getValueAs(Int::class.javaObjectType)
        val fetch = (rel.fetch as RexLiteral?)?.getValueAs(Int::class.javaObjectType)
        if (offset != null) segments += "$I2.drop($offset)\n"
        if (fetch != null) segments += "$I2.take($fetch)\n"
        return segments
    }

    private fun values(rel: Values): Chain {
        val ctx = RexContext()
        val rows = rel.tuples.map { tuple ->
            "listOf<Any?>(${tuple.joinToString(", ") { rex.expr(rex.expand(it), ctx) }})"
        }
        return Chain("listOf(${rows.joinToString(", ")})")
    }

    private fun union(rel: Union): Chain {
        val inputs = rel.inputs.map { materialize(visit(it), nameFor(it)) }
        val chain = Chain("(${inputs.joinToString(" + ")})")
        if (!rel.all) chain.segments += "$I2.distinct()\n"
        return chain
    }

    /** Base name for a materialized chain, from its most descriptive operator. */
    private fun nameFor(rel: RelNode): String = when (rel) {
        is TableScan -> rel.table!!.qualifiedName.last().lowercase()
        is Aggregate -> "grouped"
        is Filter -> "filtered"
        is Project -> nameFor(rel.input)
        is Sort -> "sorted"
        is Join -> "joined"
        is Union -> "unioned"
        is Values -> "rows"
        else -> "rel"
    }

    /** Final segment: positional rows -> named maps. */
    private fun mapToRowsSegment(fieldNames: List<String>): String {
        val pairs = fieldNames.mapIndexed { i, f -> "${kotlinString(f)} to row[$i]" }
        val oneLine = "$I2.map { row -> mapOf(${pairs.joinToString(", ")}) }\n"
        if (oneLine.length <= 100) return oneLine
        return buildString {
            append("$I2.map { row ->\n")
            append("$I2    mapOf(\n")
            pairs.forEach { append("$I2        $it,\n") }
            append("$I2    )\n")
            append("$I2}\n")
        }
    }
}
