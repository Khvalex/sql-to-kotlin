package sqlkt

import com.squareup.kotlinpoet.CodeBlock
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
import org.apache.calcite.sql.SqlKind
import org.apache.calcite.sql.type.SqlTypeName

/**
 * Walks a [RelNode] tree bottom-up and emits one Kotlin statement per operator:
 *
 * ```
 * val v0: List<List<Any?>> = tables.getValue("EMP").map { ... }   // TableScan
 * val v1 = v0.filter { row -> truth(...) }                        // Filter
 * val v2 = v1.map { row -> listOf(...) }                          // Project
 * ```
 *
 * Rows are positional (`List<Any?>`), mirroring Calcite's `RexInputRef`
 * indexing, so expression translation needs no name resolution.
 */
class RelToKotlin(private val rex: RexToKotlin) {

    private val code = CodeBlock.builder()
    private var counter = 0

    /** Emits statements for the whole tree; returns (code, name of the final variable). */
    fun generate(rel: RelNode): Pair<CodeBlock, String> {
        val result = visit(rel)
        return code.build() to result
    }

    private fun newVar(): String = "v${counter++}"

    private fun visit(rel: RelNode): String = when (rel) {
        is TableScan -> tableScan(rel)
        is Filter -> filter(rel)
        is Project -> project(rel)
        is Join -> join(rel)
        is Aggregate -> aggregate(rel)
        is Sort -> sort(rel)
        is Values -> values(rel)
        is Union -> union(rel)
        else -> throw UnsupportedOperationException(
            "Unsupported relational operator ${rel.relTypeName}: $rel"
        )
    }

    private fun tableScan(rel: TableScan): String {
        val v = newVar()
        val tableName = rel.table!!.qualifiedName.last()
        val fields = rel.rowType.fieldNames
        val extract = fields.joinToString(", ") { "r[%S]" }
        code.addStatement(
            "val $v: List<List<Any?>> = tables.getValue(%S).map { r -> listOf($extract) }",
            tableName,
            *fields.toTypedArray(),
        )
        return v
    }

    private fun filter(rel: Filter): String {
        val input = visit(rel.input)
        val v = newVar()
        code.addStatement("val $v = $input.filter { row -> truth(%L) }", rex.toExpr(rel.condition))
        return v
    }

    private fun project(rel: Project): String {
        val input = visit(rel.input)
        val v = newVar()
        val exprs = rel.projects.map { rex.toExpr(it) }
        code.addStatement(
            "val $v = $input.map { row -> listOf<Any?>(${exprs.joinToString(", ") { "%L" }}) }",
            *exprs.toTypedArray(),
        )
        return v
    }

    private fun join(rel: Join): String {
        val left = visit(rel.left)
        val right = visit(rel.right)
        val v = newVar()
        val joinType = when (rel.joinType) {
            JoinRelType.INNER -> "INNER"
            JoinRelType.LEFT -> "LEFT"
            JoinRelType.RIGHT -> "RIGHT"
            JoinRelType.FULL -> "FULL"
            JoinRelType.SEMI -> "SEMI"
            JoinRelType.ANTI -> "ANTI"
            else -> throw UnsupportedOperationException("Unsupported join type: ${rel.joinType}")
        }
        val leftArity = rel.left.rowType.fieldCount
        val rightArity = rel.right.rowType.fieldCount
        code.addStatement(
            "val $v = joinRows($left, $right, $leftArity, $rightArity, JoinType.$joinType) { row -> truth(%L) }",
            rex.toExpr(rel.condition),
        )
        return v
    }

    private fun aggregate(rel: Aggregate): String {
        require(rel.groupSets.size == 1) { "GROUPING SETS / ROLLUP / CUBE are not supported" }
        val input = visit(rel.input)
        val v = newVar()
        val keys = rel.groupSet.asList()
        val aggExprs = rel.aggCallList.map { aggCall(it) }

        if (keys.isEmpty()) {
            // Global aggregate: exactly one output row, even for empty input.
            code.addStatement(
                "val $v = listOf(listOf<Any?>(${aggExprs.joinToString(", ") { "%L" }}))",
                *aggExprs.map { it.replaceGroup(input) }.toTypedArray(),
            )
        } else {
            val keyExpr = keys.joinToString(", ") { "row[$it]" }
            val aggPart = if (aggExprs.isEmpty()) "" else
                " + listOf<Any?>(${aggExprs.joinToString(", ") { "%L" }})"
            code.addStatement(
                "val $v = $input.groupBy { row -> listOf(${keyExpr}) }.map { (key, group) -> key$aggPart }",
                *aggExprs.map { it.replaceGroup("group") }.toTypedArray(),
            )
        }
        return v
    }

    /**
     * Builds the expression for one aggregate call, with the placeholder
     * `%GROUP%` standing for the variable holding the group's rows.
     */
    private class AggExpr(val template: String) {
        fun replaceGroup(groupVar: String): String = template.replace("%GROUP%", groupVar)
    }

    private fun aggCall(call: AggregateCall): AggExpr {
        require(!call.hasFilter()) { "FILTER clause on aggregates is not supported" }
        val args = call.argList
        var values = if (args.isEmpty()) "" else "%GROUP%.map { it[${args.single()}] }"
        if (call.isDistinct && args.isNotEmpty()) values += ".distinct()"

        val expr = when (call.aggregation.kind) {
            SqlKind.COUNT ->
                if (args.isEmpty()) "%GROUP%.size.toLong()" else "aggCount($values)"
            SqlKind.SUM -> "aggSum($values)"
            SqlKind.SUM0 -> "aggSum0($values)"
            SqlKind.AVG -> "aggAvg($values)"
            SqlKind.MIN -> "aggMin($values)"
            SqlKind.MAX -> "aggMax($values)"
            SqlKind.SINGLE_VALUE -> "aggSingleValue($values)"
            else -> throw UnsupportedOperationException("Unsupported aggregate function: ${call.aggregation.name}")
        }

        // SUM/AVG/COUNT compute in Long/Double; narrow to the SQL-derived type.
        val narrowed = when (call.aggregation.kind) {
            SqlKind.SUM, SqlKind.SUM0, SqlKind.AVG, SqlKind.COUNT -> narrow(call.type.sqlTypeName, expr)
            else -> expr
        }
        return AggExpr(narrowed)
    }

    private fun narrow(type: SqlTypeName, expr: String): String = when (type) {
        SqlTypeName.TINYINT, SqlTypeName.SMALLINT, SqlTypeName.INTEGER -> "asInt($expr)"
        SqlTypeName.BIGINT -> "asLong($expr)"
        SqlTypeName.FLOAT, SqlTypeName.REAL, SqlTypeName.DOUBLE -> "asDouble($expr)"
        SqlTypeName.DECIMAL -> "asDouble($expr)"
        else -> expr
    }

    private fun sort(rel: Sort): String {
        val input = visit(rel.input)
        var v = input

        val collations = rel.collation.fieldCollations
        if (collations.isNotEmpty()) {
            val keys = collations.joinToString(", ") { fc ->
                val asc = fc.direction.isDescending.not()
                val nullsFirst = when (fc.nullDirection) {
                    RelFieldCollation.NullDirection.FIRST -> true
                    RelFieldCollation.NullDirection.LAST -> false
                    // Calcite's default: nulls sort as the largest value.
                    else -> fc.direction.isDescending
                }
                "SortKey(${fc.fieldIndex}, $asc, $nullsFirst)"
            }
            val sorted = newVar()
            code.addStatement("val $sorted = $v.sortedWith(rowComparator(listOf($keys)))")
            v = sorted
        }

        val offset = (rel.offset as RexLiteral?)?.getValueAs(Int::class.javaObjectType)
        val fetch = (rel.fetch as RexLiteral?)?.getValueAs(Int::class.javaObjectType)
        if (offset != null || fetch != null) {
            val limited = newVar()
            val ops = buildString {
                if (offset != null) append(".drop($offset)")
                if (fetch != null) append(".take($fetch)")
            }
            code.addStatement("val $limited = $v$ops")
            v = limited
        }
        return v
    }

    private fun values(rel: Values): String {
        val v = newVar()
        val rows = rel.tuples.map { tuple ->
            val cells = tuple.map { rex.toExpr(it) }
            CodeBlock.of("listOf<Any?>(${cells.joinToString(", ") { "%L" }})", *cells.toTypedArray())
        }
        code.addStatement(
            "val $v: List<List<Any?>> = listOf(${rows.joinToString(", ") { "%L" }})",
            *rows.toTypedArray(),
        )
        return v
    }

    private fun union(rel: Union): String {
        val inputs = rel.inputs.map { visit(it) }
        val v = newVar()
        val concat = inputs.joinToString(" + ")
        val distinct = if (rel.all) "" else ".distinct()"
        code.addStatement("val $v = ($concat)$distinct")
        return v
    }
}
