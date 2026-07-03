package sqlkt

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compiles a generated `.kt` source in-process with kotlin-compiler-embeddable,
 * loads it in an isolated classloader and invokes its `query` function.
 * This is the end-to-end proof that the generated code compiles and runs.
 */
object GeneratedCodeRunner {

    private val counter = AtomicInteger()

    fun compileAndRun(
        source: String,
        tables: Map<String, List<Map<String, Any?>>>,
        params: List<Any?> = emptyList(),
    ): List<Map<String, Any?>> {
        val dir = Files.createTempDirectory("sqlkt-gen-${counter.incrementAndGet()}-")
        val srcFile = dir.resolve("Query.kt").toFile()
        srcFile.writeText(source)
        val outDir = dir.resolve("classes").toFile().apply { mkdirs() }

        val messages = ByteArrayOutputStream()
        val collector = PrintingMessageCollector(PrintStream(messages), MessageRenderer.PLAIN_RELATIVE_PATHS, false)
        val args = K2JVMCompilerArguments().apply {
            freeArgs = listOf(srcFile.absolutePath)
            destination = outDir.absolutePath
            classpath = System.getProperty("java.class.path")
            noStdlib = true
            noReflect = true
            suppressWarnings = true
        }
        val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, args)
        check(exitCode == ExitCode.OK) {
            "Generated code failed to compile:\n$messages\n--- source ---\n$source"
        }

        URLClassLoader(arrayOf(outDir.toURI().toURL()), javaClass.classLoader).use { loader ->
            val cls = loader.loadClass("generated.QueryKt")
            val method = cls.getMethod("query", Map::class.java, List::class.java)
            @Suppress("UNCHECKED_CAST")
            return method.invoke(null, tables, params) as List<Map<String, Any?>>
        }
    }
}
