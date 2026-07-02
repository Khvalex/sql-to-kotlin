package sqlkt

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stage 5: DATE / TIME / TIMESTAMP, intervals, EXTRACT. */
class DateTimeTest : ConverterTestBase() {

    @Test
    fun `where date literal comparison`() {
        assertResult(
            listOf(
                mapOf("ORDER_ID" to 101),
                mapOf("ORDER_ID" to 102),
                mapOf("ORDER_ID" to 103),
                mapOf("ORDER_ID" to 105),
            ),
            "SELECT order_id FROM orders WHERE order_date >= DATE '2024-02-01'",
        )
    }

    @Test
    fun `between dates`() {
        assertResult(
            listOf(mapOf("ORDER_ID" to 101), mapOf("ORDER_ID" to 102)),
            "SELECT order_id FROM orders WHERE order_date BETWEEN DATE '2024-02-01' AND DATE '2024-02-28'",
        )
    }

    @Test
    fun `order by date desc puts nulls first`() {
        assertResultOrdered(
            listOf(
                mapOf("ORDER_ID" to 104), // NULL date sorts as largest
                mapOf("ORDER_ID" to 105),
                mapOf("ORDER_ID" to 103),
                mapOf("ORDER_ID" to 102),
                mapOf("ORDER_ID" to 101),
                mapOf("ORDER_ID" to 100),
            ),
            "SELECT order_id FROM orders ORDER BY order_date DESC",
        )
    }

    @Test
    fun `extract month and group by`() {
        assertResult(
            listOf(
                mapOf("M" to 1L, "CNT" to 1L),
                mapOf("M" to 2L, "CNT" to 2L),
                mapOf("M" to 3L, "CNT" to 2L),
                mapOf("M" to null, "CNT" to 1L),
            ),
            "SELECT EXTRACT(MONTH FROM order_date) AS m, count(*) AS cnt FROM orders " +
                "GROUP BY EXTRACT(MONTH FROM order_date)",
        )
    }

    @Test
    fun `date plus interval`() {
        assertResult(
            listOf(
                mapOf("ORDER_ID" to 100, "DUE" to LocalDate.parse("2024-01-17")),
                mapOf("ORDER_ID" to 104, "DUE" to null),
            ),
            "SELECT order_id, order_date + INTERVAL '7' DAY AS due FROM orders WHERE order_id IN (100, 104)",
        )
    }

    @Test
    fun `date minus year-month interval`() {
        assertResult(
            listOf(mapOf("PRIOR_DATE" to LocalDate.parse("2023-12-10"))),
            "SELECT order_date - INTERVAL '1' MONTH AS prior_date FROM orders WHERE order_id = 100",
        )
    }

    @Test
    fun `date difference compared to interval`() {
        // order_date - '2024-01-01' > 40 days  <=>  order_date after 2024-02-10.
        assertResult(
            listOf(
                mapOf("ORDER_ID" to 102),
                mapOf("ORDER_ID" to 103),
                mapOf("ORDER_ID" to 105),
            ),
            "SELECT order_id FROM orders WHERE (order_date - DATE '2024-01-01') DAY > INTERVAL '40' DAY",
        )
    }

    @Test
    fun `min and max dates`() {
        assertResult(
            listOf(
                mapOf(
                    "LO" to LocalDate.parse("2024-01-10"),
                    "HI" to LocalDate.parse("2024-03-15"),
                ),
            ),
            "SELECT min(order_date) AS lo, max(order_date) AS hi FROM orders",
        )
    }

    @Test
    fun `cast string to date`() {
        assertResult(
            listOf(mapOf("D" to LocalDate.parse("2024-05-01"))),
            "SELECT CAST('2024-05-01' AS DATE) AS d FROM (VALUES (0))",
        )
    }

    @Test
    fun `timestamp literal comparison`() {
        assertResult(
            listOf(mapOf("ORDER_ID" to 102), mapOf("ORDER_ID" to 105)),
            "SELECT order_id FROM orders WHERE shipped_at > TIMESTAMP '2024-02-01 00:00:00'",
        )
    }

    @Test
    fun `extract hour from timestamp`() {
        assertResult(
            listOf(mapOf("H" to 23L)),
            "SELECT EXTRACT(HOUR FROM shipped_at) AS h FROM orders WHERE order_id = 105",
        )
    }

    @Test
    fun `date in join and having`() {
        // Latest order date per employee having any order in Feb or later.
        assertResult(
            listOf(
                mapOf("NAME" to "Alice", "LAST_ORDER" to LocalDate.parse("2024-02-05")),
                mapOf("NAME" to "Bob", "LAST_ORDER" to LocalDate.parse("2024-03-15")),
                mapOf("NAME" to "Cathy", "LAST_ORDER" to LocalDate.parse("2024-03-01")),
            ),
            """
            SELECT e.name, max(o.order_date) AS last_order
            FROM emp e JOIN orders o ON o.emp_id = e.id
            GROUP BY e.name
            HAVING max(o.order_date) >= DATE '2024-02-01'
            """.trimIndent(),
        )
    }

    @Test
    fun `current_date compiles and returns a date`() {
        val rows = run("SELECT current_date AS today FROM (VALUES (0))")
        assertEquals(1, rows.size)
        assertTrue(rows[0]["TODAY"] is LocalDate, "expected LocalDate, got ${rows[0]["TODAY"]}")
    }
}
