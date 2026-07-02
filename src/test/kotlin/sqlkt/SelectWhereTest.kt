package sqlkt

import org.junit.jupiter.api.Test

/** Stage 1: SELECT / WHERE / scalar expressions. */
class SelectWhereTest : ConverterTestBase() {

    @Test
    fun `select star`() {
        assertResult(TEST_TABLES.getValue("DEPT"), "SELECT * FROM dept")
    }

    @Test
    fun `select columns with alias`() {
        assertResult(
            listOf(
                mapOf("EMP_NAME" to "Alice", "DEPTNO" to 10),
                mapOf("EMP_NAME" to "Bob", "DEPTNO" to 20),
                mapOf("EMP_NAME" to "Cathy", "DEPTNO" to 10),
                mapOf("EMP_NAME" to "Dave", "DEPTNO" to null),
                mapOf("EMP_NAME" to "Eve", "DEPTNO" to 20),
                mapOf("EMP_NAME" to null, "DEPTNO" to 30),
            ),
            "SELECT name AS emp_name, deptno FROM emp",
        )
    }

    @Test
    fun `where comparison filters out nulls`() {
        // Eve has NULL salary: NULL > 900 is UNKNOWN -> filtered out.
        assertResult(
            listOf(
                mapOf("NAME" to "Alice"),
                mapOf("NAME" to "Cathy"),
                mapOf("NAME" to null),
            ),
            "SELECT name FROM emp WHERE salary > 900",
        )
    }

    @Test
    fun `where with and or`() {
        assertResult(
            listOf(
                mapOf("NAME" to "Alice"),
                mapOf("NAME" to "Bob"),
                mapOf("NAME" to "Eve"),
            ),
            "SELECT name FROM emp WHERE active AND (deptno = 20 OR salary > 1000)",
        )
    }

    @Test
    fun `where is null and is not null`() {
        assertResult(
            listOf(mapOf("NAME" to "Dave")),
            "SELECT name FROM emp WHERE deptno IS NULL",
        )
        assertResult(
            listOf(mapOf("ID" to 6)),
            "SELECT id FROM emp WHERE name IS NULL AND age IS NULL",
        )
    }

    @Test
    fun `arithmetic in projection`() {
        assertResult(
            listOf(
                mapOf("ID" to 1, "DOUBLED" to 2400.0, "NEXT_AGE" to 31),
                mapOf("ID" to 5, "DOUBLED" to null, "NEXT_AGE" to 29),
            ),
            "SELECT id, salary * 2 AS doubled, age + 1 AS next_age FROM emp WHERE id IN (1, 5)",
        )
    }

    @Test
    fun `in list and between`() {
        assertResult(
            listOf(mapOf("NAME" to "Bob"), mapOf("NAME" to "Eve")),
            "SELECT name FROM emp WHERE age BETWEEN 25 AND 28",
        )
        assertResult(
            listOf(mapOf("NAME" to "Alice"), mapOf("NAME" to "Cathy")),
            "SELECT name FROM emp WHERE deptno IN (10, 40)",
        )
    }

    @Test
    fun `not and inequality`() {
        assertResult(
            listOf(mapOf("NAME" to "Alice"), mapOf("NAME" to "Cathy")),
            "SELECT name FROM emp WHERE NOT (deptno <> 10)",
        )
    }

    @Test
    fun `string functions and concatenation`() {
        assertResult(
            listOf(mapOf("X" to "ALICE!", "N" to 5, "S" to "lic")),
            "SELECT upper(name) || '!' AS x, char_length(name) AS n, substring(name FROM 2 FOR 3) AS s " +
                "FROM emp WHERE id = 1",
        )
    }

    @Test
    fun `like patterns`() {
        assertResult(
            listOf(mapOf("NAME" to "Alice")),
            "SELECT name FROM emp WHERE name LIKE 'Al%'",
        )
        assertResult(
            listOf(mapOf("NAME" to "Cathy")),
            "SELECT name FROM emp WHERE name LIKE '_athy'",
        )
    }

    @Test
    fun `literal projection from values`() {
        assertResult(
            listOf(mapOf("A" to 1, "B" to "x")),
            "SELECT 1 AS a, 'x' AS b FROM (VALUES (0))",
        )
    }
}
