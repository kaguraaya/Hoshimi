package com.acgcompass.architecture

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

/**
 * 类的 `init` 块按声明顺序执行：写在 `init` 后面的属性此时还没初始化（是 null）。
 * ViewModel 在 `init` 里 `viewModelScope.launch` 时协程会立刻在主线程跑到第一个挂起点，
 * 读到这种属性就会空指针、一打开页面就闪退，编译器不报错，所以用源码扫描拦住。
 */
class InitOrderRulesTest : StringSpec({
    "init blocks do not use properties declared after them" {
        val sourceRoot = findAppRoot().resolve("src/main/java/com/acgcompass")
        val violations = Files.walk(sourceRoot).use { paths ->
            paths
                .filter(Path::isRegularFile)
                .filter { it.toString().endsWith(".kt") }
                .flatMap { file ->
                    val relative = sourceRoot.relativize(file).toString().replace('\\', '/')
                    initOrderViolations(file.readLines()).map { "$relative|$it" }.stream()
                }
                .toList()
        }
        if (violations.isNotEmpty()) {
            error("Properties used in init before they are declared:\n${violations.joinToString("\n")}")
        }
        violations.shouldBeEmpty()
    }

    "detects a property declared after init" {
        val source = """
            class Sample(private val repo: Repo) {
                init {
                    scope.launch { lists.value = repo.load() }
                }

                private val lists = MutableStateFlow<List<Int>?>(null)
            }
        """.trimIndent().lines()
        initOrderViolations(source) shouldContainExactly listOf("lists")
    }

    "ignores constructor parameters and earlier properties" {
        val source = """
            class Sample(
                rules: List<Int>,
            ) {
                private val early = 1

                init {
                    require(rules.isNotEmpty() && early > 0)
                }

                private val rules = rules.sorted()
            }
        """.trimIndent().lines()
        initOrderViolations(source).shouldBeEmpty()
    }
})

private val CLASS_HEADER = Regex("""^(?:\w+\s+)*class\s""")

private val MEMBER_PROPERTY =
    Regex("""^ {4}(?:(?:private|internal|protected|public|override|open|lateinit)\s+)*(?:val|var)\s+(\w+)""")

/** 返回在顶层类 `init` 块里用到、却声明在它后面的属性名。 */
internal fun initOrderViolations(lines: List<String>): List<String> {
    val result = mutableListOf<String>()
    lines.forEachIndexed { index, line ->
        if (line != "    init {") return@forEachIndexed
        val initEnd = blockEnd(lines, index)
        val classEnd = (initEnd until lines.size).firstOrNull { lines[it] == "}" } ?: lines.size
        val classStart = (index downTo 0).firstOrNull { CLASS_HEADER.containsMatchIn(lines[it]) } ?: 0
        val headerEnd = (classStart until index).firstOrNull { lines[it].trimEnd().endsWith("{") } ?: index
        val header = lines.subList(classStart, headerEnd + 1)
        val body = lines.subList(index + 1, initEnd).joinToString("\n") { it.substringBefore("//") }
        lines.subList(initEnd, classEnd)
            .mapNotNull { MEMBER_PROPERTY.find(it)?.groupValues?.get(1) }
            .filterNot { name -> header.any { Regex("""\b$name\s*:""").containsMatchIn(it) } }
            .filter { name -> Regex("""(?<![.\w])$name\b""").containsMatchIn(body) }
            .forEach(result::add)
    }
    return result.distinct()
}

private fun blockEnd(lines: List<String>, start: Int): Int {
    var depth = 0
    for (i in start until lines.size) {
        val code = lines[i].substringBefore("//")
        depth += code.count { it == '{' } - code.count { it == '}' }
        if (depth == 0) return i
    }
    return lines.size
}
