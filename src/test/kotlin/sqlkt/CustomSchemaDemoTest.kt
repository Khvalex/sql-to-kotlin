package sqlkt

import org.apache.calcite.sql.type.SqlTypeName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Showcase: two real-world queries on a user-defined schema, with the exact
 * generated Kotlin embedded below each test. The tests still compile and run
 * the generated code against sample data, so the embedded listings are backed
 * by a green build; fresh copies are also written to build/demo/ on each run.
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

    /**
     * JDBC-style placeholders: values arrive through the params argument,
     * their Kotlin types are inferred by the validator from context.
     *
     * SQL:
     * ```
     * SELECT id, city, price, area FROM items
     * WHERE area > ? AND city = ? ORDER BY price LIMIT 100
     * ```
     *
     * Generated (plus the runtime prelude in the same file):
     * ```
     * private data class ItemsRow(
     *     val id: Int?,
     *     val city: String?,
     *     val price: Double?,
     *     val area: Double?,
     * )
     *
     * fun query(tables: Map<String, List<Map<String, Any?>>>, params: List<Any?> = emptyList()): List<Map<String, Any?>> {
     *     return tables.getValue("ITEMS")
     *         .map { r ->
     *             ItemsRow(
     *                 r["ID"] as Int?,
     *                 r["CITY"] as String?,
     *                 r["PRICE"] as Double?,
     *                 r["AREA"] as Double?,
     *             )
     *         }
     *         .filter { row ->
     *             truth(gt(row.area, (params[0] as Double?))) && truth(eq(row.city, (params[1] as String?)))
     *         }
     *         .sortedWith(orderBy<ItemsRow>({ it.price }, asc = true, nullsFirst = false))
     *         .take(100)
     *         .map { row -> mapOf("ID" to row.id, "CITY" to row.city, "PRICE" to row.price, "AREA" to row.area) }
     * }
     * ```
     */
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

    /**
     * Correlated scalar sub-query: Calcite decorrelates the per-product AVG
     * into a standalone aggregation (val grouped) joined back on productid
     * with the amount > avgAmount predicate.
     *
     * SQL (LIMIT is a literal — the converter bakes it in at generation time):
     * ```
     * SELECT a.id, a.amount, c.region, p.category
     * FROM applications a
     * JOIN clients c ON a.clientId = c.id
     * JOIN products p ON a.productId = p.id
     * WHERE c.segment = 'affluent'
     *   AND a.amount > (SELECT AVG(a2.amount) FROM applications a2 WHERE a2.productId = a.productId)
     * ORDER BY a.amount DESC
     * LIMIT 10
     * ```
     *
     * Generated (plus the runtime prelude in the same file):
     * ```
     * private data class ApplicationsRow(
     *     val id: Int?,
     *     val clientid: Int?,
     *     val productid: Int?,
     *     val amount: Double?,
     * )
     *
     * private data class ClientsRow(
     *     val id: Int?,
     *     val region: String?,
     *     val segment: String?,
     * )
     *
     * private data class JoinedRow(
     *     val id: Int?,
     *     val clientid: Int?,
     *     val productid: Int?,
     *     val amount: Double?,
     *     val id2: Int?,
     *     val region: String?,
     *     val segment: String?,
     * )
     *
     * private data class ProductsRow(
     *     val id: Int?,
     *     val category: String?,
     * )
     *
     * private data class JoinedRow2(
     *     val id: Int?,
     *     val clientid: Int?,
     *     val productid: Int?,
     *     val amount: Double?,
     *     val id2: Int?,
     *     val region: String?,
     *     val segment: String?,
     *     val id3: Int?,
     *     val category: String?,
     * )
     *
     * private data class Row(
     *     val productid: Int?,
     *     val amount: Double?,
     * )
     *
     * private data class GroupedRow(
     *     val productid: Int?,
     *     val avgAmount: Double?,
     * )
     *
     * private data class JoinedRow3(
     *     val id: Int?,
     *     val clientid: Int?,
     *     val productid: Int?,
     *     val amount: Double?,
     *     val id2: Int?,
     *     val region: String?,
     *     val segment: String?,
     *     val id3: Int?,
     *     val category: String?,
     *     val productid2: Int?,
     *     val avgAmount: Double?,
     * )
     *
     * private data class Row2(
     *     val id: Int?,
     *     val amount: Double?,
     *     val region: String?,
     *     val category: String?,
     * )
     *
     * fun query(tables: Map<String, List<Map<String, Any?>>>, params: List<Any?> = emptyList()): List<Map<String, Any?>> {
     *     val applications = tables.getValue("APPLICATIONS")
     *         .map { r ->
     *             ApplicationsRow(
     *                 r["ID"] as Int?,
     *                 r["CLIENTID"] as Int?,
     *                 r["PRODUCTID"] as Int?,
     *                 r["AMOUNT"] as Double?,
     *             )
     *         }
     *     val filtered = tables.getValue("CLIENTS")
     *         .map { r -> ClientsRow(r["ID"] as Int?, r["REGION"] as String?, r["SEGMENT"] as String?) }
     *         .filter { row -> truth(eq(row.segment, "affluent")) }
     *     val joined = innerJoin(applications, filtered, on = { l, r -> truth(eq(l.clientid, r.id)) }) { l, r ->
     *         JoinedRow(l.id, l.clientid, l.productid, l.amount, r.id, r.region, r.segment)
     *     }
     *     val products = tables.getValue("PRODUCTS")
     *         .map { r -> ProductsRow(r["ID"] as Int?, r["CATEGORY"] as String?) }
     *     val joined2 = innerJoin(joined, products, on = { l, r -> truth(eq(l.productid, r.id)) }) { l, r ->
     *         JoinedRow2(l.id, l.clientid, l.productid, l.amount, l.id2, l.region, l.segment, r.id, r.category)
     *     }
     *     val grouped = applications
     *         .filter { row -> row.productid != null }
     *         .map { row -> Row(productid = row.productid, amount = row.amount) }
     *         .groupBy { row -> row.productid }
     *         .map { (productid, group) ->
     *             GroupedRow(
     *                 productid = productid,
     *                 avgAmount = aggAvg(group.map { it.amount }),
     *             )
     *         }
     *     val joined3 = innerJoin(joined2, grouped, on = { l, r -> truth(eq(l.productid, r.productid)) && truth(gt(l.amount, r.avgAmount)) }) { l, r ->
     *         JoinedRow3(
     *             l.id,
     *             l.clientid,
     *             l.productid,
     *             l.amount,
     *             l.id2,
     *             l.region,
     *             l.segment,
     *             l.id3,
     *             l.category,
     *             r.productid,
     *             r.avgAmount,
     *         )
     *     }
     *     return joined3
     *         .map { row -> Row2(id = row.id, amount = row.amount, region = row.region, category = row.category) }
     *         .sortedWith(orderBy<Row2>({ it.amount }, asc = false, nullsFirst = true))
     *         .take(10)
     *         .map { row ->
     *             mapOf(
     *                 "ID" to row.id,
     *                 "AMOUNT" to row.amount,
     *                 "REGION" to row.region,
     *                 "CATEGORY" to row.category,
     *             )
     *         }
     * }
     * ```
     */
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
