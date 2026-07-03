package sqlkt

import org.junit.jupiter.api.Test

/** Stage 4: CASE WHEN, sub-queries (incl. correlated), CTE. */
class SubqueryCteTest : ConverterTestBase() {

    @Test
    fun `case when in projection`() {
        assertResult(
            listOf(
                mapOf("NAME" to "Alice", "BAND" to "high"),
                mapOf("NAME" to "Bob", "BAND" to "low"),
                mapOf("NAME" to "Cathy", "BAND" to "high"),
                mapOf("NAME" to "Dave", "BAND" to "low"),
                mapOf("NAME" to "Eve", "BAND" to "unknown"),
                mapOf("NAME" to null, "BAND" to "low"),
            ),
            """
            SELECT name,
                   CASE WHEN salary >= 1000 THEN 'high'
                        WHEN salary IS NULL THEN 'unknown'
                        ELSE 'low' END AS band
            FROM emp
            """.trimIndent(),
        )
    }

    @Test
    fun `case when inside aggregate input`() {
        assertResult(
            // SUM over INTEGER derives INTEGER in Calcite -> Int, not Long.
            listOf(mapOf("ACTIVE_CNT" to 4)),
            "SELECT sum(CASE WHEN active THEN 1 ELSE 0 END) AS active_cnt FROM emp",
        )
    }

    @Test
    fun `coalesce and nullif`() {
        assertResult(
            listOf(
                mapOf("ID" to 4, "DEPT_OR_ZERO" to 0),
                mapOf("ID" to 5, "DEPT_OR_ZERO" to 20),
            ),
            "SELECT id, coalesce(deptno, 0) AS dept_or_zero FROM emp WHERE id IN (4, 5)",
        )
    }

    @Test
    fun `subquery in from clause`() {
        assertResult(
            listOf(
                mapOf("NAME" to "Alice", "TAXED" to 960.0),
                mapOf("NAME" to "Cathy", "TAXED" to 1200.0),
            ),
            """
            SELECT name, taxed
            FROM (SELECT name, salary * 0.8 AS taxed FROM emp WHERE deptno = 10) t
            WHERE taxed > 900
            """.trimIndent(),
        )
    }

    @Test
    fun `uncorrelated in subquery`() {
        // Departments with budget > 6000: only 10.
        assertResult(
            listOf(mapOf("NAME" to "Alice"), mapOf("NAME" to "Cathy")),
            "SELECT name FROM emp WHERE deptno IN (SELECT deptno FROM dept WHERE budget > 6000)",
        )
    }

    @Test
    fun `uncorrelated scalar subquery`() {
        assertResult(
            listOf(mapOf("NAME" to "Cathy")),
            "SELECT name FROM emp WHERE salary = (SELECT max(salary) FROM emp)",
        )
    }

    @Test
    fun `correlated exists subquery`() {
        // Employees that have at least one SHIPPED order.
        assertResult(
            listOf(mapOf("NAME" to "Alice"), mapOf("NAME" to "Bob")),
            """
            SELECT name FROM emp e
            WHERE EXISTS (SELECT 1 FROM orders o WHERE o.emp_id = e.id AND o.status = 'SHIPPED')
            """.trimIndent(),
        )
    }

    @Test
    fun `correlated not exists subquery`() {
        assertResult(
            listOf(
                mapOf("NAME" to "Dave"),
                mapOf("NAME" to "Eve"),
                mapOf("NAME" to null),
            ),
            """
            SELECT name FROM emp e
            WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.emp_id = e.id)
            """.trimIndent(),
        )
    }

    @Test
    fun `correlated scalar subquery in select list`() {
        assertResult(
            listOf(
                mapOf("NAME" to "Alice", "ORDER_CNT" to 2L),
                mapOf("NAME" to "Bob", "ORDER_CNT" to 2L),
                mapOf("NAME" to "Cathy", "ORDER_CNT" to 1L),
                mapOf("NAME" to "Dave", "ORDER_CNT" to 0L),
            ),
            """
            SELECT name, (SELECT count(*) FROM orders o WHERE o.emp_id = e.id) AS order_cnt
            FROM emp e
            WHERE e.id <= 4
            """.trimIndent(),
        )
    }

    @Test
    fun `cte single`() {
        assertResult(
            listOf(
                mapOf("DNAME" to "Engineering", "CNT" to 2L),
                mapOf("DNAME" to "Sales", "CNT" to 2L),
                mapOf("DNAME" to "Marketing", "CNT" to 1L),
            ),
            """
            WITH dept_size AS (
                SELECT deptno, count(*) AS cnt FROM emp GROUP BY deptno
            )
            SELECT d.dname, s.cnt
            FROM dept_size s
            JOIN dept d ON d.deptno = s.deptno
            """.trimIndent(),
        )
    }

    @Test
    fun `cte referencing another cte`() {
        assertResult(
            listOf(mapOf("NAME" to "Alice"), mapOf("NAME" to "Cathy")),
            """
            WITH rich AS (SELECT * FROM emp WHERE salary > 1000),
                 rich_eng AS (SELECT r.* FROM rich r JOIN dept d ON r.deptno = d.deptno
                              WHERE d.dname = 'Engineering')
            SELECT name FROM rich_eng
            """.trimIndent(),
        )
    }

    @Test
    fun `union of two queries`() {
        assertResult(
            listOf(
                mapOf("NAME" to "Alice"),
                mapOf("NAME" to "Cathy"),
                mapOf("NAME" to "Engineering"),
                mapOf("NAME" to "Sales"),
            ),
            "SELECT name FROM emp WHERE deptno = 10 " +
                "UNION SELECT dname FROM dept WHERE budget >= 5000",
        )
    }
}
