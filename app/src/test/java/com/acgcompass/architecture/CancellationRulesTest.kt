package com.acgcompass.architecture

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

/** 业务代码里不用标准库 `runCatching`：它会把协程取消当成普通失败吞掉。统一用 `runCatchingCancellable`。 */
class CancellationRulesTest : StringSpec({
    "main sources do not use runCatching that swallows cancellation" {
        val sourceRoot = findAppRoot().resolve("src/main/java/com/acgcompass")
        val pattern = Regex("""(?<![\w.])runCatching\s*\{""")
        val offenders = Files.walk(sourceRoot).use { paths ->
            paths
                .filter(Path::isRegularFile)
                .filter { it.toString().endsWith(".kt") }
                .toList()
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        "${sourceRoot.relativize(file)}:${index + 1}".takeIf { pattern.containsMatchIn(line) }
                    }
                }
        }
        offenders.shouldBeEmpty()
    }
})
