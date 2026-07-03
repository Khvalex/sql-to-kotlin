package sqlkt

import org.junit.jupiter.api.Test

/** Stage 2: JOINs (equality conditions). */
class JoinTest : ConverterTestBase() {

    @Test
    fun `inner join`() {
        assertResult(
            listOf(
                mapOf("NAME" to "Alice", "DNAME" to "Engineering"),
                mapOf("NAME" to "Bob", "DNAME" to "Sales"),
                mapOf("NAME" to "Cathy", "DNAME" to "Engineering"),
                mapOf("NAME" to "Eve", "DNAME" to "Sales"),
                mapOf("NAME" to null, "DNAME" to "Marketing"),
            ),
            "SELECT e.name, d.dname FROM emp e JOIN dept d ON e.deptno = d.deptno",
        )
    }

    @Test
    fun `left join keeps unmatched left rows`() {
        // Dave has NULL deptno -> no match -> DNAME null.
        assertResult(
            listOf(
                mapOf("NAME" to "Alice", "DNAME" to "Engineering"),
                mapOf("NAME" to "Bob", "DNAME" to "Sales"),
                mapOf("NAME" to "Cathy", "DNAME" to "Engineering"),
                mapOf("NAME" to "Dave", "DNAME" to null),
                mapOf("NAME" to "Eve", "DNAME" to "Sales"),
                mapOf("NAME" to null, "DNAME" to "Marketing"),
            ),
            "SELECT e.name, d.dname FROM emp e LEFT JOIN dept d ON e.deptno = d.deptno",
        )
    }

    @Test
    fun `right join keeps unmatched right rows`() {
        assertResult(
            listOf(
                mapOf("NAME" to "Alice", "DNAME" to "Engineering"),
                mapOf("NAME" to "Bob", "DNAME" to "Sales"),
                mapOf("NAME" to "Cathy", "DNAME" to "Engineering"),
                mapOf("NAME" to "Eve", "DNAME" to "Sales"),
                mapOf("NAME" to null, "DNAME" to "Marketing"),
                mapOf("NAME" to null, "DNAME" to "Empty"),
            ),
            "SELECT e.name, d.dname FROM emp e RIGHT JOIN dept d ON e.deptno = d.deptno",
        )
    }

    @Test
    fun `full outer join`() {
        assertResult(
            listOf(
                mapOf("NAME" to "Alice", "DNAME" to "Engineering"),
                mapOf("NAME" to "Bob", "DNAME" to "Sales"),
                mapOf("NAME" to "Cathy", "DNAME" to "Engineering"),
                mapOf("NAME" to "Dave", "DNAME" to null),
                mapOf("NAME" to "Eve", "DNAME" to "Sales"),
                mapOf("NAME" to null, "DNAME" to "Marketing"),
                mapOf("NAME" to null, "DNAME" to "Empty"),
            ),
            "SELECT e.name, d.dname FROM emp e FULL JOIN dept d ON e.deptno = d.deptno",
        )
    }

    @Test
    fun `join with additional filter in on clause`() {
        assertResult(
            listOf(
                mapOf("NAME" to "Alice", "DNAME" to "Engineering"),
                mapOf("NAME" to "Cathy", "DNAME" to "Engineering"),
            ),
            "SELECT e.name, d.dname FROM emp e JOIN dept d ON e.deptno = d.deptno AND d.budget > 6000",
        )
    }

    @Test
    fun `three way join`() {
        assertResult(
            listOf(
                mapOf("NAME" to "Alice", "DNAME" to "Engineering", "AMOUNT" to 250.0),
                mapOf("NAME" to "Alice", "DNAME" to "Engineering", "AMOUNT" to 100.0),
                mapOf("NAME" to "Bob", "DNAME" to "Sales", "AMOUNT" to 75.0),
                mapOf("NAME" to "Bob", "DNAME" to "Sales", "AMOUNT" to null),
                mapOf("NAME" to "Cathy", "DNAME" to "Engineering", "AMOUNT" to 300.0),
            ),
            """
            SELECT e.name, d.dname, o.amount
            FROM emp e
            JOIN dept d ON e.deptno = d.deptno
            JOIN orders o ON o.emp_id = e.id
            """.trimIndent(),
        )
    }

    @Test
    fun `join with where on both sides`() {
        assertResult(
            listOf(mapOf("NAME" to "Cathy", "DNAME" to "Engineering")),
            "SELECT e.name, d.dname FROM emp e JOIN dept d ON e.deptno = d.deptno " +
                "WHERE d.budget > 6000 AND e.salary > 1300",
        )
    }
}
