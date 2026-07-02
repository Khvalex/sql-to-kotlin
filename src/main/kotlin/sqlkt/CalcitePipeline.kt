package sqlkt

import org.apache.calcite.config.CalciteConnectionConfigImpl
import org.apache.calcite.jdbc.CalciteSchema
import org.apache.calcite.plan.RelOptCluster
import org.apache.calcite.plan.hep.HepPlanner
import org.apache.calcite.plan.hep.HepProgramBuilder
import org.apache.calcite.prepare.CalciteCatalogReader
import org.apache.calcite.rel.RelNode
import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.rel.type.RelDataTypeSystem
import org.apache.calcite.rex.RexBuilder
import org.apache.calcite.schema.impl.AbstractTable
import org.apache.calcite.sql.`fun`.SqlStdOperatorTable
import org.apache.calcite.sql.parser.SqlParser
import org.apache.calcite.sql.type.SqlTypeFactoryImpl
import org.apache.calcite.sql.validate.SqlValidator
import org.apache.calcite.sql.validate.SqlValidatorUtil
import org.apache.calcite.sql2rel.SqlToRelConverter
import org.apache.calcite.sql2rel.StandardConvertletTable
import java.util.Properties

/**
 * SQL text -> validated, typed relational tree (RelNode).
 *
 * Uses only the standalone parts of Calcite: parser, validator and
 * SqlToRelConverter. No planner rules, no executor, no JDBC.
 */
class CalcitePipeline(schema: SqlSchema) {

    val typeFactory: RelDataTypeFactory = SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT)
    val rexBuilder = RexBuilder(typeFactory)

    private val catalogReader: CalciteCatalogReader

    init {
        val rootSchema = CalciteSchema.createRootSchema(false, false)
        for (table in schema.tables) {
            rootSchema.add(table.name, object : AbstractTable() {
                override fun getRowType(tf: RelDataTypeFactory): RelDataType = table.rowType(tf)
            })
        }
        catalogReader = CalciteCatalogReader(
            rootSchema,
            emptyList(),
            typeFactory,
            CalciteConnectionConfigImpl(Properties()),
        )
    }

    /** Result of the frontend: the final RelNode plus the output field names in order. */
    data class Result(val rel: RelNode, val fieldNames: List<String>)

    fun sqlToRel(sql: String): Result {
        val parsed = SqlParser.create(sql).parseQuery()

        val validator = SqlValidatorUtil.newValidator(
            SqlStdOperatorTable.instance(),
            catalogReader,
            typeFactory,
            SqlValidator.Config.DEFAULT.withIdentifierExpansion(true),
        )
        val validated = validator.validate(parsed)

        // A planner instance is required by RelOptCluster, but we never run it.
        val planner = HepPlanner(HepProgramBuilder().build())
        val cluster = RelOptCluster.create(planner, rexBuilder)

        val config = SqlToRelConverter.config()
            // Expand sub-queries into joins/aggregates so codegen only sees the
            // closed set of relational operators, never RexSubQuery.
            .withExpand(true)
            .withTrimUnusedFields(false)
        val converter = SqlToRelConverter(
            null, // no view expansion
            validator,
            catalogReader,
            cluster,
            StandardConvertletTable.INSTANCE,
            config,
        )

        var root = converter.convertQuery(validated, false, true)
        // Turn LogicalCorrelate (correlated sub-queries) into joins where possible.
        root = root.withRel(converter.decorrelate(validated, root.rel))
        val fieldNames = root.fields.map { it.value }
        return Result(root.project(), fieldNames)
    }
}
