package sqlkt

import org.apache.calcite.sql.type.SqlTypeName

/**
 * Demo entry point: converts the SQL passed as the first argument (or a demo
 * query) against a sample schema and prints the generated Kotlin source.
 */
fun main(args: Array<String>) {
    val schema = SqlSchema(
        listOf(
            TableDef(
                "EMP",
                listOf(
                    Column("ID", SqlTypeName.INTEGER, nullable = false),
                    Column("NAME", SqlTypeName.VARCHAR),
                    Column("DEPTNO", SqlTypeName.INTEGER),
                    Column("SALARY", SqlTypeName.DOUBLE),
                ),
            ),
            TableDef(
                "DEPT",
                listOf(
                    Column("DEPTNO", SqlTypeName.INTEGER, nullable = false),
                    Column("DNAME", SqlTypeName.VARCHAR),
                ),
            ),
        ),
    )
    val sql = args.firstOrNull() ?: """
        SELECT d.dname, count(*) AS cnt, avg(e.salary) AS avg_sal
        FROM emp e
        JOIN dept d ON e.deptno = d.deptno
        WHERE e.salary > 500
        GROUP BY d.dname
        HAVING count(*) >= 1
        ORDER BY avg_sal DESC
        LIMIT 10
    """.trimIndent()

    println(SqlToKotlinConverter(schema).convert(sql))
}
