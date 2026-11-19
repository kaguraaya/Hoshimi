package com.acgcompass.architecture

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

class DependencyRulesTest : StringSpec({
    "new code respects layer dependency direction" {
        val appRoot = findAppRoot()
        val sourceRoot = appRoot.resolve("src/main/java/com/acgcompass")
        val baseline = appRoot.resolve("config/architecture-baseline.txt")
            .readLines()
            .filterNot { it.isBlank() || it.startsWith('#') }
            .toSet()

        val violations = Files.walk(sourceRoot).use { paths ->
            paths
                .filter(Path::isRegularFile)
                .filter { it.toString().endsWith(".kt") }
                .flatMap { file -> violationsIn(sourceRoot, file).stream() }
                .toList()
                .toSet()
        }
        val regressions = violations - baseline

        if (regressions.isNotEmpty()) {
            error("New cross-layer references are not allowed:\n${regressions.sorted().joinToString("\n")}")
        }
        regressions.shouldBeEmpty()
    }
})

private fun violationsIn(sourceRoot: Path, file: Path): List<String> {
    val relative = sourceRoot.relativize(file).toString().replace('\\', '/')
    val layer = relative.substringBefore('/')
    val code = stripComments(file.readLines())
    val imports = code
        .filter { it.startsWith("import ") }
        .filter { import -> isForbiddenImport(layer, import) }
        .map { "$relative|$it" }
    val fullyQualifiedReferences = code
        .filterNot { it.startsWith("import ") }
        .flatMap { line -> forbiddenReferences(layer, line).asSequence() }
        .map { "$relative|$it" }
    return (imports + fullyQualifiedReferences).distinct()
}

private fun isForbiddenImport(layer: String, import: String): Boolean = when (layer) {
    "core" -> import.startsWith("import com.acgcompass.data.")
    "domain" -> import.startsWith("import com.acgcompass.data.") || import.startsWith("import android.")
    "feature" ->
        import.startsWith("import com.acgcompass.data.remote.") ||
            import.startsWith("import com.acgcompass.data.local.")
    else -> false
}

private fun forbiddenReferences(layer: String, line: String): List<String> {
    val typeReference = when (layer) {
        "core", "domain" -> Regex(
            "com\\.acgcompass\\.data\\.(?:[a-z][A-Za-z0-9_]*\\.)*[A-Z][A-Za-z0-9_]*",
        )
        "feature" -> Regex(
            "com\\.acgcompass\\.data\\.(?:remote|local)\\.(?:[a-z][A-Za-z0-9_]*\\.)*[A-Z][A-Za-z0-9_]*",
        )
        else -> return emptyList()
    }
    return typeReference.findAll(line).map { it.value }.toList()
}

private fun stripComments(lines: List<String>): List<String> {
    var inBlockComment = false
    return lines.map { line ->
        var index = 0
        val code = StringBuilder()
        while (index < line.length) {
            if (inBlockComment) {
                val end = line.indexOf("*/", index)
                if (end < 0) return@map ""
                inBlockComment = false
                index = end + 2
            } else {
                when {
                    line.startsWith("//", index) -> break
                    line.startsWith("/*", index) -> {
                        inBlockComment = true
                        index += 2
                    }
                    else -> code.append(line[index++])
                }
            }
        }
        code.toString().trim()
    }
}

internal fun findAppRoot(): Path {
    val workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath()
    return sequenceOf(workingDirectory, workingDirectory.resolve("app"))
        .first { Files.exists(it.resolve("src/main/java")) }
}
