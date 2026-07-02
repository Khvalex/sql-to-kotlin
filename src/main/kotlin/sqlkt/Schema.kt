package sqlkt

import org.apache.calcite.rel.type.RelDataType
import org.apache.calcite.rel.type.RelDataTypeFactory
import org.apache.calcite.sql.type.SqlTypeName

/**
 * Description of a "table" the converter knows about. Column names are matched
 * case-sensitively after the parser normalizes unquoted identifiers to upper case,
 * so define them in UPPER CASE.
 */
data class Column(
    val name: String,
    val type: SqlTypeName,
    val nullable: Boolean = true,
)

data class TableDef(
    val name: String,
    val columns: List<Column>,
) {
    fun rowType(typeFactory: RelDataTypeFactory): RelDataType {
        val builder = typeFactory.builder()
        for (c in columns) {
            builder.add(c.name, typeFactory.createTypeWithNullability(typeFactory.createSqlType(c.type), c.nullable))
        }
        return builder.build()
    }
}

data class SqlSchema(val tables: List<TableDef>)
