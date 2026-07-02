package sqlkt

import org.junit.jupiter.api.Test

/** Stage 3: aggregates, GROUP BY, HAVING, ORDER BY, LIMIT, DISTINCT. */
class AggregateTest : ConverterTestBase() {

    @Test
    fun `global aggregates`() {
        // COUNT(*) counts all rows, COUNT(salary)/AVG/SUM/MIN/MAX ignore NULLs.
        assertResult(
            listOf(
                mapOf(
                    "CNT" to 6L,
                    "CNT_SAL" to 5L,
                    "TOTAL" to 5150.0,
                    "AVERAGE" to 1030.0,
                    "LO" to 700.0,
                    "HI" to 1500.0,
                ),
            ),
            "SELECT count(*) AS cnt, count(salary) AS cnt_sal, sum(salary) AS total, " +
                "avg(salary) AS average, min(salary) AS lo, max(salary) AS hi FROM emp",
        )
    }

    @Test
    fun `global aggregate over empty input`() {
        // SUM over the empty set is NULL, COUNT is 0 — still exactly one row.
        assertResult(
            listOf(mapOf("CNT" to 0L, "TOTAL" to null)),
            "SELECT count(*) AS cnt, sum(salary) AS total FROM emp WHERE id > 1000",
        )
    }

    @Test
    fun `group by with multiple aggregates`() {
        assertResult(
            listOf(
                mapOf("DEPTNO" to 10, "CNT" to 2L, "TOTAL" to 2700.0),
                mapOf("DEPTNO" to 20, "CNT" to 2L, "TOTAL" to 800.0),
                mapOf("DEPTNO" to 30, "CNT" to 1L, "TOTAL" to 950.0),
                mapOf("DEPTNO" to null, "CNT" to 1L, "TOTAL" to 700.0),
            ),
            "SELECT deptno, count(*) AS cnt, sum(salary) AS total FROM emp GROUP BY deptno",
        )
    }

    @Test
    fun `integer sum and avg stay integer typed`() {
        // Calcite derives INTEGER for SUM/AVG over INTEGER; avg truncates.
        assertResult(
            listOf(mapOf("TOTAL" to 158, "AVERAGE" to 31)),
            "SELECT sum(age) AS total, avg(age) AS average FROM emp",
        )
    }

    @Test
    fun `count distinct`() {
        assertResult(
            listOf(mapOf("D" to 3L)),
            "SELECT count(DISTINCT deptno) AS d FROM emp",
        )
    }

    @Test
    fun `having filters groups`() {
        assertResult(
            listOf(mapOf("DEPTNO" to 10, "TOTAL" to 2700.0)),
            "SELECT deptno, sum(salary) AS total FROM emp GROUP BY deptno HAVING sum(salary) > 1000 AND deptno IS NOT NULL",
        )
    }

    @Test
    fun `group by expression`() {
        assertResult(
            listOf(
                mapOf("SENIOR" to true, "CNT" to 3L),
                mapOf("SENIOR" to false, "CNT" to 2L),
                mapOf("SENIOR" to null, "CNT" to 1L),
            ),
            "SELECT age >= 30 AS senior, count(*) AS cnt FROM emp GROUP BY age >= 30",
        )
    }

    @Test
    fun `select distinct`() {
        assertResult(
            listOf(
                mapOf("DEPTNO" to 10),
                mapOf("DEPTNO" to 20),
                mapOf("DEPTNO" to 30),
                mapOf("DEPTNO" to null),
            ),
            "SELECT DISTINCT deptno FROM emp",
        )
    }

    @Test
    fun `order by with nulls and direction`() {
        // Calcite default: NULLs sort as the largest value, so DESC puts them first.
        assertResultOrdered(
            listOf(
                mapOf("NAME" to "Eve", "SALARY" to null),
                mapOf("NAME" to "Cathy", "SALARY" to 1500.0),
                mapOf("NAME" to "Alice", "SALARY" to 1200.0),
                mapOf("NAME" to null, "SALARY" to 950.0),
                mapOf("NAME" to "Bob", "SALARY" to 800.0),
                mapOf("NAME" to "Dave", "SALARY" to 700.0),
            ),
            "SELECT name, salary FROM emp ORDER BY salary DESC",
        )
    }

    @Test
    fun `order by two keys with explicit nulls first`() {
        assertResultOrdered(
            listOf(
                mapOf("NAME" to "Dave", "DEPTNO" to null),
                mapOf("NAME" to "Cathy", "DEPTNO" to 10),
                mapOf("NAME" to "Alice", "DEPTNO" to 10),
                // Eve's NULL salary sorts as largest -> first under DESC.
                mapOf("NAME" to "Eve", "DEPTNO" to 20),
                mapOf("NAME" to "Bob", "DEPTNO" to 20),
                mapOf("NAME" to null, "DEPTNO" to 30),
            ),
            "SELECT name, deptno FROM emp ORDER BY deptno ASC NULLS FIRST, salary DESC",
        )
    }

    @Test
    fun `limit and offset`() {
        assertResultOrdered(
            listOf(
                mapOf("ID" to 3),
                mapOf("ID" to 4),
            ),
            "SELECT id FROM emp ORDER BY id LIMIT 2 OFFSET 2",
        )
    }

    @Test
    fun `order by aggregate over join`() {
        assertResultOrdered(
            listOf(
                mapOf("DNAME" to "Engineering", "TOTAL" to 650.0),
                mapOf("DNAME" to "Sales", "TOTAL" to 75.0),
            ),
            """
            SELECT d.dname, sum(o.amount) AS total
            FROM orders o
            JOIN emp e ON o.emp_id = e.id
            JOIN dept d ON e.deptno = d.deptno
            GROUP BY d.dname
            ORDER BY total DESC
            """.trimIndent(),
        )
    }
}
