package sqlkt

import org.apache.calcite.sql.type.SqlTypeName
import java.time.LocalDate
import java.time.LocalDateTime

/** Synthetic test schema: three tables with a mix of types and nullable columns. */
val TEST_SCHEMA = SqlSchema(
    listOf(
        TableDef(
            "EMP",
            listOf(
                Column("ID", SqlTypeName.INTEGER, nullable = false),
                Column("NAME", SqlTypeName.VARCHAR),
                Column("DEPTNO", SqlTypeName.INTEGER),
                Column("SALARY", SqlTypeName.DOUBLE),
                Column("AGE", SqlTypeName.INTEGER),
                Column("ACTIVE", SqlTypeName.BOOLEAN),
            ),
        ),
        TableDef(
            "DEPT",
            listOf(
                Column("DEPTNO", SqlTypeName.INTEGER, nullable = false),
                Column("DNAME", SqlTypeName.VARCHAR),
                Column("BUDGET", SqlTypeName.DOUBLE),
            ),
        ),
        TableDef(
            "ORDERS",
            listOf(
                Column("ORDER_ID", SqlTypeName.INTEGER, nullable = false),
                Column("EMP_ID", SqlTypeName.INTEGER),
                Column("AMOUNT", SqlTypeName.DOUBLE),
                Column("STATUS", SqlTypeName.VARCHAR),
                Column("ORDER_DATE", SqlTypeName.DATE),
                Column("SHIPPED_AT", SqlTypeName.TIMESTAMP),
            ),
        ),
    ),
)

fun emp(id: Int, name: String?, deptno: Int?, salary: Double?, age: Int?, active: Boolean?): Map<String, Any?> =
    mapOf("ID" to id, "NAME" to name, "DEPTNO" to deptno, "SALARY" to salary, "AGE" to age, "ACTIVE" to active)

fun dept(deptno: Int, dname: String?, budget: Double?): Map<String, Any?> =
    mapOf("DEPTNO" to deptno, "DNAME" to dname, "BUDGET" to budget)

fun order(
    orderId: Int,
    empId: Int?,
    amount: Double?,
    status: String?,
    orderDate: LocalDate? = null,
    shippedAt: LocalDateTime? = null,
): Map<String, Any?> = mapOf(
    "ORDER_ID" to orderId, "EMP_ID" to empId, "AMOUNT" to amount, "STATUS" to status,
    "ORDER_DATE" to orderDate, "SHIPPED_AT" to shippedAt,
)

/** Shared test data. Includes nulls to exercise SQL null semantics. */
val TEST_TABLES: Map<String, List<Map<String, Any?>>> = mapOf(
    "EMP" to listOf(
        emp(1, "Alice", 10, 1200.0, 30, true),
        emp(2, "Bob", 20, 800.0, 25, true),
        emp(3, "Cathy", 10, 1500.0, 35, false),
        emp(4, "Dave", null, 700.0, 40, true),
        emp(5, "Eve", 20, null, 28, true),
        emp(6, null, 30, 950.0, null, null),
    ),
    "DEPT" to listOf(
        dept(10, "Engineering", 10000.0),
        dept(20, "Sales", 5000.0),
        dept(30, "Marketing", null),
        dept(40, "Empty", 1000.0),
    ),
    "ORDERS" to listOf(
        order(100, 1, 250.0, "SHIPPED", LocalDate.parse("2024-01-10"), LocalDateTime.parse("2024-01-11T10:30:00")),
        order(101, 1, 100.0, "PENDING", LocalDate.parse("2024-02-05")),
        order(102, 2, 75.0, "SHIPPED", LocalDate.parse("2024-02-20"), LocalDateTime.parse("2024-02-21T08:00:00")),
        order(103, 3, 300.0, "CANCELLED", LocalDate.parse("2024-03-01")),
        order(104, null, 50.0, "PENDING"),
        order(105, 2, null, "SHIPPED", LocalDate.parse("2024-03-15"), LocalDateTime.parse("2024-03-15T23:45:00")),
    ),
)
