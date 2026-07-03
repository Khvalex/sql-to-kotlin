package sqlkt

import org.junit.jupiter.api.Test

/** Stage 6: math functions — POWER/SQRT/ROUND/FLOOR/CEIL, logs, trig. */
class MathTest : ConverterTestBase() {

    @Test
    fun `rounding functions`() {
        assertResult(
            listOf(
                // FLOOR/CEIL over DECIMAL derive DECIMAL(scale=0) -> integral (Long).
                mapOf("R" to 2.57, "F" to 2L, "C" to -2L, "S" to -1, "T" to 2.56),
            ),
            "SELECT round(2.567, 2) AS r, floor(2.9) AS f, ceiling(-2.1) AS c, " +
                "sign(-5) AS s, truncate(2.567, 2) AS t FROM (VALUES (0))",
        )
    }

    @Test
    fun `power sqrt exp ln`() {
        assertResult(
            listOf(mapOf("P" to 1024.0, "Q" to 12.0, "E" to 2.718, "L" to 2.0)),
            "SELECT power(2, 10) AS p, sqrt(144) AS q, round(exp(1), 3) AS e, " +
                "round(ln(exp(2)), 1) AS l FROM (VALUES (0))",
        )
    }

    @Test
    fun `trigonometry and pi`() {
        assertResult(
            listOf(mapOf("S" to 1.0, "C" to 1.0, "PI4" to 3.141593, "D" to 180.0)),
            // PI is niladic in Calcite: no parentheses.
            "SELECT round(sin(pi / 2), 6) AS s, round(cos(0), 6) AS c, " +
                "round(atan2(1, 1) * 4, 6) AS pi4, round(degrees(pi), 3) AS d FROM (VALUES (0))",
        )
    }

    @Test
    fun `math over nullable column propagates null`() {
        assertResult(
            listOf(
                mapOf("ID" to 1, "R" to 34.64),
                mapOf("ID" to 5, "R" to null),
            ),
            "SELECT id, round(sqrt(salary), 2) AS r FROM emp WHERE id IN (1, 5)",
        )
    }

    @Test
    fun `log10 and mod`() {
        assertResult(
            listOf(mapOf("LG" to 3.0, "M" to 2)),
            "SELECT round(log10(1000), 1) AS lg, mod(17, 5) AS m FROM (VALUES (0))",
        )
    }

    /**
     * Medium-complexity "algorithmics": per-department salary deviation.
     * CTE chain + join + aggregates + POWER/SQRT/ROUND + CASE + ORDER BY/LIMIT.
     */
    @Test
    fun `salary deviation analytics query`() {
        assertResultOrdered(
            listOf(
                mapOf("NAME" to "Cathy", "SALARY" to 1500.0, "DEPT_AVG" to 1350.0,
                    "SQ_DEV" to 22500.0, "DEV_PCT" to 11.1, "FLAG" to "normal"),
                mapOf("NAME" to "Alice", "SALARY" to 1200.0, "DEPT_AVG" to 1350.0,
                    "SQ_DEV" to 22500.0, "DEV_PCT" to 11.1, "FLAG" to "normal"),
                mapOf("NAME" to null, "SALARY" to 950.0, "DEPT_AVG" to 950.0,
                    "SQ_DEV" to 0.0, "DEV_PCT" to 0.0, "FLAG" to "normal"),
                mapOf("NAME" to "Bob", "SALARY" to 800.0, "DEPT_AVG" to 800.0,
                    "SQ_DEV" to 0.0, "DEV_PCT" to 0.0, "FLAG" to "normal"),
            ),
            """
            WITH stats AS (
                SELECT deptno, avg(salary) AS avg_sal
                FROM emp
                WHERE salary IS NOT NULL
                GROUP BY deptno
            ),
            scored AS (
                SELECT e.name, e.salary, s.avg_sal,
                       round(power(e.salary - s.avg_sal, 2), 2) AS sq_dev,
                       round(sqrt(power(e.salary - s.avg_sal, 2)) / s.avg_sal * 100, 1) AS dev_pct
                FROM emp e
                JOIN stats s ON e.deptno = s.deptno
                WHERE e.salary IS NOT NULL
            )
            SELECT name, salary, round(avg_sal, 1) AS dept_avg, sq_dev, dev_pct,
                   CASE WHEN dev_pct > 20 THEN 'outlier' ELSE 'normal' END AS flag
            FROM scored
            ORDER BY dev_pct DESC, salary DESC
            LIMIT 5
            """.trimIndent(),
        )
    }
}
