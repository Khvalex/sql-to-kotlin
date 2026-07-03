package sqlkt

import org.apache.calcite.sql.type.SqlTypeName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * End-to-end demo on a user-defined schema: two joins + correlated scalar
 * sub-query (per-product average) + ORDER BY/LIMIT. Also dumps the generated
 * source to build/demo/ for inspection.
 */
class CustomSchemaDemoTest {

    private val schema = SqlSchema(
        listOf(
            TableDef(
                "APPLICATIONS",
                listOf(
                    Column("ID", SqlTypeName.INTEGER, nullable = false),
                    Column("CLIENTID", SqlTypeName.INTEGER),
                    Column("PRODUCTID", SqlTypeName.INTEGER),
                    Column("AMOUNT", SqlTypeName.DOUBLE),
                ),
            ),
            TableDef(
                "CLIENTS",
                listOf(
                    Column("ID", SqlTypeName.INTEGER, nullable = false),
                    Column("REGION", SqlTypeName.VARCHAR),
                    Column("SEGMENT", SqlTypeName.VARCHAR),
                ),
            ),
            TableDef(
                "PRODUCTS",
                listOf(
                    Column("ID", SqlTypeName.INTEGER, nullable = false),
                    Column("CATEGORY", SqlTypeName.VARCHAR),
                ),
            ),
        ),
    )

    private fun app(id: Int, clientId: Int?, productId: Int?, amount: Double?): Map<String, Any?> =
        mapOf("ID" to id, "CLIENTID" to clientId, "PRODUCTID" to productId, "AMOUNT" to amount)

    private val tables: Map<String, List<Map<String, Any?>>> = mapOf(
        "APPLICATIONS" to listOf(
            app(1, 100, 1, 5000.0),
            app(2, 101, 1, 1000.0),
            app(3, 100, 2, 800.0),
            app(4, 102, 2, 1200.0),
            app(5, 101, 1, 3000.0),
            app(6, 102, 1, null), // NULL amount: ignored by AVG, filtered by >
        ),
        "CLIENTS" to listOf(
            mapOf("ID" to 100, "REGION" to "EU", "SEGMENT" to "affluent"),
            mapOf("ID" to 101, "REGION" to "US", "SEGMENT" to "mass"),
            mapOf("ID" to 102, "REGION" to "EU", "SEGMENT" to "affluent"),
        ),
        "PRODUCTS" to listOf(
            mapOf("ID" to 1, "CATEGORY" to "loan"),
            mapOf("ID" to 2, "CATEGORY" to "card"),
        ),
    )

    @Test
    fun `parameterized items query with jdbc placeholders`() {
        val itemsSchema = SqlSchema(
            listOf(
                TableDef(
                    "ITEMS",
                    listOf(
                        Column("ID", SqlTypeName.INTEGER, nullable = false),
                        Column("CITY", SqlTypeName.VARCHAR),
                        Column("PRICE", SqlTypeName.DOUBLE),
                        Column("AREA", SqlTypeName.DOUBLE),
                    ),
                ),
            ),
        )
        val items = mapOf(
            "ITEMS" to listOf(
                mapOf("ID" to 1, "CITY" to "Berlin", "PRICE" to 900.0, "AREA" to 70.0),
                mapOf("ID" to 2, "CITY" to "Berlin", "PRICE" to 500.0, "AREA" to 40.0),
                mapOf("ID" to 3, "CITY" to "Berlin", "PRICE" to 700.0, "AREA" to 55.0),
                mapOf("ID" to 4, "CITY" to "Munich", "PRICE" to 800.0, "AREA" to 80.0),
                mapOf("ID" to 5, "CITY" to "Berlin", "PRICE" to null, "AREA" to 90.0),
            ),
        )
        val sql = "SELECT id, city, price, area FROM items " +
            "WHERE area > ? AND city = ? ORDER BY price LIMIT 100"

        val source = SqlToKotlinConverter(itemsSchema).convert(sql)
        File("build/demo").mkdirs()
        File("build/demo/ItemsQuery.kt").writeText(source)

        assertEquals(
            listOf(
                mapOf("ID" to 3, "CITY" to "Berlin", "PRICE" to 700.0, "AREA" to 55.0),
                mapOf("ID" to 1, "CITY" to "Berlin", "PRICE" to 900.0, "AREA" to 70.0),
                mapOf("ID" to 5, "CITY" to "Berlin", "PRICE" to null, "AREA" to 90.0),
            ),
            GeneratedCodeRunner.compileAndRun(source, items, params = listOf(50.0, "Berlin")),
        )
    }

    @Test
    fun `applications above per-product average for affluent clients`() {
        val sql = """
            SELECT a.id, a.amount, c.region, p.category
            FROM applications a
            JOIN clients c ON a.clientId = c.id
            JOIN products p ON a.productId = p.id
            WHERE c.segment = 'affluent'
              AND a.amount > (SELECT AVG(a2.amount) FROM applications a2 WHERE a2.productId = a.productId)
            ORDER BY a.amount DESC
            LIMIT 10
        """.trimIndent()

        val source = SqlToKotlinConverter(schema).convert(sql)
        File("build/demo").mkdirs()
        File("build/demo/ApplicationsQuery.kt").writeText(source)

        // AVG per product: loan = (5000+1000+3000)/3 = 3000, card = (800+1200)/2 = 1000.
        // Affluent applications above their product's average: #1 (5000 > 3000), #4 (1200 > 1000).
        assertEquals(
            listOf(
                mapOf("ID" to 1, "AMOUNT" to 5000.0, "REGION" to "EU", "CATEGORY" to "loan"),
                mapOf("ID" to 4, "AMOUNT" to 1200.0, "REGION" to "EU", "CATEGORY" to "card"),
            ),
            GeneratedCodeRunner.compileAndRun(source, tables),
        )
    }
}
