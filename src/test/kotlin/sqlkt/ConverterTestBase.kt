package sqlkt

import kotlin.test.assertEquals

abstract class ConverterTestBase {

    private val converter = SqlToKotlinConverter(TEST_SCHEMA)

    /** SQL -> generated .kt -> compile -> run against [TEST_TABLES]. */
    protected fun run(sql: String, params: List<Any?> = emptyList()): List<Map<String, Any?>> {
        val source = converter.convert(sql)
        return GeneratedCodeRunner.compileAndRun(source, TEST_TABLES, params)
    }

    /** For queries with ORDER BY: row order matters. */
    protected fun assertResultOrdered(expected: List<Map<String, Any?>>, sql: String) {
        assertEquals(expected, run(sql), "SQL: $sql")
    }

    /** For queries without ORDER BY: compare as multisets. */
    protected fun assertResult(expected: List<Map<String, Any?>>, sql: String) {
        val actual = run(sql)
        assertEquals(
            expected.groupingBy { it }.eachCount(),
            actual.groupingBy { it }.eachCount(),
            "SQL: $sql\nactual rows: $actual",
        )
    }
}
