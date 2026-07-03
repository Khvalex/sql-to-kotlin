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
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rex.RexLiteral
import org.apache.calcite.rex.RexNode
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.type.SqlTypeName

private const val I1 = "    " // statement indent inside the function
private const val I2 = "        " // chained operator indent

/**
 * Walks a [RelNode] tree bottom-up and emits typed, idiomatic-looking Kotlin.
 *
 * Every operator boundary gets a generated `data class` derived from Calcite's
 * validated row type, so expressions read `row.salary` instead of `row[3]`.
 * Linear operator runs become one fluent collection chain; joins/unions and
 * twice-scanned tables materialize into named `val`s; subexpressions repeated
 * within a projection are extracted into a single local.
 */
class RelToKotlin(private val rex: RexToKotlin) {

    /** A generated row data class: property names/types parallel the field indexes. */
    private class RowClass(val name: String, val props: List<String>, val types: List<String>)

    /** A fluent chain: head expression plus `.op(...)` segments producing [rowClass] rows. */
    private class Chain(val head: String, var rowClass: RowClass, val segments: MutableList<String> = mutableListOf())

    private val out = StringBuilder()
    private val decls = StringBuilder()
    private val usedVarNames = mutableMapOf<String, Int>()
    private val usedClassNames = mutableMapOf<String, Int>()
    private val classBySignature = mutableMapOf<String, RowClass>()

    /** Tables scanned more than once are materialized into one shared `val`. */
    private val scanCounts = mutableMapOf<String, Int>()
    private val scanVars = mutableMapOf<String, Chain>()

    /** Emits the data class declarations and the function body; returns (decls, function). */
    fun generate(rel: RelNode, fieldNames: List<String>, functionName: String): Pair<String, String> {
        countScans(rel)
        out.append(
            "fun $functionName(tables: Map<String, List<Map<String, Any?>>>, " +
                "params: List<Any?> = emptyList()): List<Map<String, Any?>> {\n",
        )
        val chain = visit(rel)
        chain.segments += finalMapSegment(fieldNames, chain.rowClass)
        out.append("${I1}return ${chain.head}\n")
        chain.segments.forEach { out.append(it) }
        out.append("}\n")
        return decls.toString() to out.toString()
    }

    // ------------------------------------------------------------------ util

    private fun freshVar(base: String): String {
        val n = usedVarNames.merge(base, 1, Int::plus)!!
        return if (n == 1) base else "$base$n"
    }

    private fun freshClassName(base: String): String {
        val n = usedClassNames.merge(base, 1, Int::plus)!!
        return if (n == 1) base else "$base$n"
    }

    /** Emits `val <name> = <chain>` unless the chain is already a bare variable. */
    private fun materialize(chain: Chain, base: String): String {
        if (chain.segments.isEmpty() && chain.head.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) {
            return chain.head
        }
        val name = freshVar(base)
        out.append("${I1}val $name = ${chain.head}\n")
        chain.segments.forEach { out.append(it) }
        return name
    }

    private val kotlinKeywords = setOf(
        "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
        "interface", "is", "null", "object", "package", "return", "super", "this", "throw",
        "true", "try", "typealias", "val", "var", "when", "while",
        // names already used by the generated code and prelude
        "row", "key", "group", "tables", "it", "r", "l", "truth",
    )

    /** SQL field name -> Kotlin identifier: DEV_PCT -> devPct, $f0 -> f0, devPct -> devPct. */
    private fun localName(field: String, taken: MutableSet<String>): String {
        val parts = field.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
        var name = parts.withIndex().joinToString("") { (i, p) ->
            // Mixed-case parts are already camelCase — keep them as-is.
            val base = if (p.any { it.isLowerCase() } && p.any { it.isUpperCase() }) p else p.lowercase()
            if (i == 0) base.replaceFirstChar { it.lowercase() } else base.replaceFirstChar { it.uppercase() }
        }.ifEmpty { "col" }
        if (name.first().isDigit()) name = "c$name"
        if (name in kotlinKeywords) name += "_"
        var unique = name
        var n = 2
        while (!taken.add(unique)) unique = "$name${n++}"
        return unique
    }

    /** Gets or creates the data class for a row shape. Identical shapes share one class. */
    private fun rowClassOf(fieldNames: List<String>, types: List<String>, hint: String): RowClass {
        val taken = mutableSetOf<String>()
        val props = fieldNames.map { localName(it, taken) }
        val signature = props.zip(types).joinToString(";") { "${it.first}:${it.second}" }
        classBySignature[signature]?.let { return it }

        val cls = RowClass(freshClassName(hint), props, types)
        decls.append("private data class ${cls.name}(\n")
        props.zip(types).forEach { (p, t) -> decls.append("    val $p: $t,\n") }
        decls.append(")\n\n")
        classBySignature[signature] = cls
        return cls
    }

    private fun rowClassOf(rowType: RelDataType, hint: String): RowClass =
        rowClassOf(rowType.fieldNames, rowType.fieldList.map { kotlinType(it.type) }, hint)

    /** Bindings for a lambda over [cls] rows: field index -> `param.prop`. */
    private fun bindings(param: String, cls: RowClass): Map<Int, String> =
        cls.props.withIndex().associate { (i, p) -> i to "$param.$p" }

    /**
     * Locals for common subexpressions repeated across [exprs], named after the
     * projected alias when the subexpression IS a projected column.
     */
    private fun sharedLocals(
        exprs: List<RexNode>,
        base: RexContext,
        outputProps: List<String?> = emptyList(),
        indent: String,
    ): Pair<List<String>, RexContext> {
        val taken = mutableSetOf("row", "key", "group", "l", "r", "it")
        val lines = mutableListOf<String>()
        var shared = base.shared
        for (node in rex.sharedSubtrees(exprs)) {
            val digest = node.toString()
            val alias = exprs.indexOfFirst { it.toString() == digest }
                .let { outputProps.getOrNull(it) }
            val name = localName(alias ?: "shared", taken)
            lines += "${indent}val $name = ${rex.expr(node, base.copy(shared = shared, indent = indent))}"
            shared = shared + (digest to name)
        }
        return lines to base.copy(shared = shared, indent = indent)
    }

    // ------------------------------------------------------------- operators

    private fun visit(rel: RelNode): Chain = when (rel) {
        is TableScan -> tableScan(rel)
        is Filter -> visit(rel.input).also { it.segments += filterSegment(rel, it.rowClass) }
        is Project -> visit(rel.input).also {
            // Identity projections (same fields, same order) emit nothing;
            // output aliases still surface via the final field names.
            if (!org.apache.calcite.rex.RexUtil.isIdentity(rel.projects, rel.input.rowType)) {
                val (segment, outCls) = projectSegment(rel.projects, rel.rowType, it.rowClass)
                it.segments += segment
                it.rowClass = outCls
            }
        }
        is Join -> join(rel)
        is Aggregate -> aggregate(rel)
        is Sort -> visit(rel.input).also { it.segments += sortSegments(rel, it.rowClass) }
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
        scanVars[tableName]?.let { return Chain(it.head, it.rowClass) }

        val hint = tableName.lowercase().replaceFirstChar { it.uppercase() } + "Row"
        val cls = rowClassOf(rel.rowType, hint)
        val cells = rel.rowType.fieldNames.mapIndexed { i, f -> "r[${kotlinString(f)}] as ${cls.types[i]}" }

        val oneLine = "$I2.map { r -> ${cls.name}(${cells.joinToString(", ")}) }\n"
        val segment = if (oneLine.length <= 110) oneLine else buildString {
            append("$I2.map { r ->\n")
            append("$I2    ${cls.name}(\n")
            cells.forEach { append("$I2        $it,\n") }
            append("$I2    )\n")
            append("$I2}\n")
        }
        val chain = Chain("tables.getValue(${kotlinString(tableName)})", cls, mutableListOf(segment))
        // A table scanned twice becomes one shared `val` instead of two copies.
        if (scanCounts.getOrDefault(tableName, 0) > 1) {
            val name = materialize(chain, tableName.lowercase())
            val cached = Chain(name, cls)
            scanVars[tableName] = cached
            return Chain(name, cls)
        }
        return chain
    }

    private fun filterSegment(rel: Filter, cls: RowClass): String {
        val cond = rex.expand(rel.condition)
        val ctx = RexContext(bindings("row", cls))
        val (locals, localCtx) = sharedLocals(listOf(cond), ctx, indent = "$I2    ")
        if (locals.isEmpty()) {
            val oneLine = "$I2.filter { row -> ${rex.condition(cond, ctx.copy(indent = "$I2    "))} }\n"
            if (oneLine.length <= 110 && '\n' !in oneLine.dropLast(1)) return oneLine
        }
        return buildString {
            append("$I2.filter { row ->\n")
            locals.forEach { append("$it\n") }
            append("$I2    ${rex.condition(cond, localCtx)}\n")
            append("$I2}\n")
        }
    }

    private fun projectSegment(
        projects: List<RexNode>,
        rowType: RelDataType,
        inputCls: RowClass,
    ): Pair<String, RowClass> {
        val exprs = projects.map { rex.expand(it) }
        val outCls = rowClassOf(rowType, "Row")

        val param = if (exprs.any { rex.inputRefs(it).isNotEmpty() }) "row" else "_"
        val ctx = RexContext(bindings("row", inputCls))
        val (locals, localCtx) = sharedLocals(exprs, ctx, outCls.props, indent = "$I2    ")

        val cellCtx = localCtx.copy(indent = "$I2        ")
        val args = exprs.mapIndexed { i, e -> "${outCls.props[i]} = ${rex.expr(e, cellCtx)}" }

        if (locals.isEmpty()) {
            val oneLine = "$I2.map { $param -> ${outCls.name}(${args.joinToString(", ")}) }\n"
            if (oneLine.length <= 110 && '\n' !in oneLine.dropLast(1)) return oneLine to outCls
        }
        return buildString {
            append("$I2.map { $param ->\n")
            locals.forEach { append("$it\n") }
            append("$I2    ${outCls.name}(\n")
            args.forEach { append("$I2        $it,\n") }
            append("$I2    )\n")
            append("$I2}\n")
        } to outCls
    }

    private fun join(rel: Join): Chain {
        val leftChain = visit(rel.left)
        val leftCls = leftChain.rowClass
        val left = materialize(leftChain, nameFor(rel.left))
        val rightChain = visit(rel.right)
        val rightCls = rightChain.rowClass
        val right = materialize(rightChain, nameFor(rel.right))

        val leftArity = leftCls.props.size
        val cond = rex.expand(rel.condition)
        val condBindings = buildMap {
            for (i in rex.inputRefs(cond)) {
                if (i < leftArity) put(i, "l.${leftCls.props[i]}") else put(i, "r.${rightCls.props[i - leftArity]}")
            }
        }
        val condText = rex.condition(cond, RexContext(condBindings, indent = "$I1    "))

        // SEMI/ANTI keep the left row type; no combiner needed.
        if (rel.joinType == JoinRelType.SEMI || rel.joinType == JoinRelType.ANTI) {
            val fn = if (rel.joinType == JoinRelType.SEMI) "semiJoin" else "antiJoin"
            return Chain("$fn($left, $right) { l, r -> $condText }", leftCls)
        }

        val fn = when (rel.joinType) {
            JoinRelType.INNER -> "innerJoin"
            JoinRelType.LEFT -> "leftJoin"
            JoinRelType.RIGHT -> "rightJoin"
            JoinRelType.FULL -> "fullJoin"
            else -> throw UnsupportedOperationException("Unsupported join type: ${rel.joinType}")
        }
        val lAcc = if (rel.joinType == JoinRelType.RIGHT || rel.joinType == JoinRelType.FULL) "l?" else "l"
        val rAcc = if (rel.joinType == JoinRelType.LEFT || rel.joinType == JoinRelType.FULL) "r?" else "r"

        // Inherit the (possibly synthesized) property names of both sides
        // instead of Calcite's uniquified raw names, so e.g. an aggregate's
        // avgAmount keeps its name through the join.
        val cls = rowClassOf(leftCls.props + rightCls.props, leftCls.types + rightCls.types, "JoinedRow")
        val args = cls.props.indices.map { i ->
            if (i < leftArity) "$lAcc.${leftCls.props[i]}" else "$rAcc.${rightCls.props[i - leftArity]}"
        }

        val name = freshVar("joined")
        out.append("${I1}val $name = $fn($left, $right, on = { l, r -> $condText }) { l, r ->\n")
        val oneLine = "$I1    ${cls.name}(${args.joinToString(", ")})\n"
        if (oneLine.length <= 110) {
            out.append(oneLine)
        } else {
            out.append("$I1    ${cls.name}(\n")
            args.forEach { out.append("$I1        $it,\n") }
            out.append("$I1    )\n")
        }
        out.append("$I1}\n")
        return Chain(name, cls)
    }

    /**
     * Output field names for an aggregate: Calcite's internal EXPR$N / $fN
     * names are replaced with names derived from the call (`avgAmount`).
     */
    private fun aggOutputFields(rel: Aggregate, inCls: RowClass): List<String> {
        val keys = rel.groupSet.asList()
        return rel.rowType.fieldNames.mapIndexed { i, raw ->
            if (i < keys.size || !(raw.startsWith("EXPR$") || raw.startsWith("\$f"))) return@mapIndexed raw
            val call = rel.aggCallList[i - keys.size]
            val fn = call.aggregation.name.lowercase()
            val arg = call.argList.singleOrNull()?.let { inCls.props[it] }
            if (arg == null) fn else fn + arg.replaceFirstChar { it.uppercase() }
        }
    }

    private fun aggregateRowClass(rel: Aggregate, inCls: RowClass): RowClass =
        rowClassOf(aggOutputFields(rel, inCls), rel.rowType.fieldList.map { kotlinType(it.type) }, "GroupedRow")

    private fun aggregate(rel: Aggregate): Chain {
        require(rel.groupSets.size == 1) { "GROUPING SETS / ROLLUP / CUBE are not supported" }
        val keys = rel.groupSet.asList()

        // Global aggregate: exactly one output row, even for empty input.
        if (keys.isEmpty()) {
            val inputChain = visit(rel.input)
            val inCls = inputChain.rowClass
            val input = materialize(inputChain, nameFor(rel.input))
            val cls = aggregateRowClass(rel, inCls)
            val args = rel.aggCallList.mapIndexed { i, call ->
                "${cls.props[i]} = ${aggExpr(call, input, inCls)}"
            }
            val name = freshVar("aggregated")
            val oneLine = "${I1}val $name = listOf(${cls.name}(${args.joinToString(", ")}))\n"
            if (oneLine.length <= 110) {
                out.append(oneLine)
            } else {
                out.append("${I1}val $name = listOf(\n")
                out.append("$I1    ${cls.name}(\n")
                args.forEach { out.append("$I1        $it,\n") }
                out.append("$I1    ),\n")
                out.append("$I1)\n")
            }
            return Chain(name, cls)
        }

        val chain = visit(rel.input)
        val inCls = chain.rowClass
        val outCls = aggregateRowClass(rel, inCls)

        // SELECT DISTINCT: group keys only, no aggregate calls.
        if (rel.aggCallList.isEmpty()) {
            val args = keys.mapIndexed { j, k -> "${outCls.props[j]} = row.${inCls.props[k]}" }
            chain.segments += "$I2.map { row -> ${outCls.name}(${args.joinToString(", ")}) }\n"
            chain.segments += "$I2.distinct()\n"
            chain.rowClass = outCls
            return chain
        }

        val keyRefs: List<String>
        val mapParams: String
        if (keys.size == 1) {
            chain.segments += "$I2.groupBy { row -> row.${inCls.props[keys[0]]} }\n"
            val keyParam = outCls.props[0].let { if (it == "group") "${it}Key" else it }
            keyRefs = listOf(keyParam)
            mapParams = "($keyParam, group)"
        } else {
            val keyCls = rowClassOf(
                keys.map { inCls.props[it].uppercase() },
                keys.map { inCls.types[it] },
                "GroupKey",
            )
            val keyArgs = keys.joinToString(", ") { "row.${inCls.props[it]}" }
            chain.segments += "$I2.groupBy { row -> ${keyCls.name}($keyArgs) }\n"
            keyRefs = keyCls.props.map { "key.$it" }
            mapParams = "(key, group)"
        }

        val args = outCls.props.mapIndexed { i, p ->
            if (i < keys.size) "$p = ${keyRefs[i]}"
            else "$p = ${aggExpr(rel.aggCallList[i - keys.size], "group", inCls)}"
        }
        val oneLine = "$I2.map { $mapParams -> ${outCls.name}(${args.joinToString(", ")}) }\n"
        chain.segments += if (oneLine.length <= 110) oneLine else buildString {
            append("$I2.map { $mapParams ->\n")
            append("$I2    ${outCls.name}(\n")
            args.forEach { append("$I2        $it,\n") }
            append("$I2    )\n")
            append("$I2}\n")
        }
        chain.rowClass = outCls
        return chain
    }

    private fun aggExpr(call: AggregateCall, groupVar: String, inCls: RowClass): String {
        require(!call.hasFilter()) { "FILTER clause on aggregates is not supported" }
        val args = call.argList
        var values = if (args.isEmpty()) "" else "$groupVar.map { it.${inCls.props[args.single()]} }"
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

        // COUNT is already Long, AVG already Double; SUM computes in Long/Double
        // and narrows to the SQL-derived type. MIN/MAX/SINGLE_VALUE are generic.
        return when (call.aggregation.kind) {
            SqlKind.SUM, SqlKind.SUM0 -> narrowAgg(call.type, expr)
            SqlKind.AVG -> when (call.type.sqlTypeName) {
                SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE -> expr
                else -> narrowAgg(call.type, expr)
            }
            else -> expr
        }
    }

    private fun narrowAgg(type: RelDataType, expr: String): String = when (type.sqlTypeName) {
        SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER -> "asInt($expr)"
        SqlTypeName.BIGINT -> "asLong($expr)"
        SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE -> "asDouble($expr)"
        SqlTypeName.DECIMAL -> if (type.scale > 0) "asDouble($expr)" else "asLong($expr)"
        else -> expr
    }

    private fun sortSegments(rel: Sort, cls: RowClass): List<String> {
        val segments = mutableListOf<String>()

        val collations = rel.collation.fieldCollations
        if (collations.isNotEmpty()) {
            val comparators = collations.mapIndexed { i, fc ->
                val asc = !fc.direction.isDescending
                val nullsFirst = when (fc.nullDirection) {
                    RelFieldCollation.NullDirection.FIRST -> true
                    RelFieldCollation.NullDirection.LAST -> false
                    // Calcite's default: nulls sort as the largest value.
                    else -> fc.direction.isDescending
                }
                val fn = if (i == 0) "orderBy<${cls.name}>" else ".thenOrderBy"
                "$fn({ it.${cls.props[fc.fieldIndex]} }, asc = $asc, nullsFirst = $nullsFirst)"
            }
            segments += if (comparators.size == 1) {
                "$I2.sortedWith(${comparators.single()})\n"
            } else {
                buildString {
                    append("$I2.sortedWith(\n")
                    append("$I2    ${comparators.first()}\n")
                    comparators.drop(1).forEach { append("$I2        $it\n") }
                    append("$I2)\n")
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
        val cls = rowClassOf(rel.rowType, "ValuesRow")
        if (rel.tuples.isEmpty()) return Chain("emptyList<${cls.name}>()", cls)
        val ctx = RexContext()
        val rows = rel.tuples.map { tuple ->
            "${cls.name}(${tuple.joinToString(", ") { rex.expr(rex.expand(it), ctx) }})"
        }
        return Chain("listOf(${rows.joinToString(", ")})", cls)
    }

    private fun union(rel: Union): Chain {
        val parts = mutableListOf<String>()
        var target: RowClass? = null
        for (input in rel.inputs) {
            val chain = visit(input)
            val cls = chain.rowClass
            val v = materialize(chain, nameFor(input))
            if (target == null) {
                target = cls
                parts += v
            } else if (cls === target) {
                parts += v
            } else {
                // Same shape, different class: re-wrap positionally.
                val t = target
                parts += "$v.map { ${t.name}(${cls.props.joinToString(", ") { "it.$it" }}) }"
            }
        }
        val chain = Chain("(${parts.joinToString(" + ")})", target!!)
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

    /** Final segment: typed rows -> named maps (the function's public contract). */
    private fun finalMapSegment(fieldNames: List<String>, cls: RowClass): String {
        val pairs = fieldNames.mapIndexed { i, f -> "${kotlinString(f)} to row.${cls.props[i]}" }
        val oneLine = "$I2.map { row -> mapOf(${pairs.joinToString(", ")}) }\n"
        if (oneLine.length <= 110) return oneLine
        return buildString {
            append("$I2.map { row ->\n")
            append("$I2    mapOf(\n")
            pairs.forEach { append("$I2        $it,\n") }
            append("$I2    )\n")
            append("$I2}\n")
        }
    }
}
