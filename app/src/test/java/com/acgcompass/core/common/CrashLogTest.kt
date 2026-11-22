package com.acgcompass.core.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files

/** 本地异常记录：写入、读回、只留最近 10 条、堆栈截断；没初始化时什么都不做。 */
class CrashLogTest : StringSpec({
    "records an exception with its stack and reads it back" {
        CrashLog.init(Files.createTempDirectory("crash").toFile())
        CrashLog.record("闪退", IllegalStateException("boom"), thread = "main")
        val entry = CrashLog.entries().single()
        entry.header shouldContain "闪退"
        entry.summary shouldStartWith "java.lang.IllegalStateException: boom"
        entry.summary shouldContain "线程 main"
        (entry.detail.first() shouldContain "IllegalStateException")
        CrashLog.clear()
        CrashLog.entries() shouldBe emptyList()
    }

    "keeps only the latest entries and trims long details" {
        CrashLog.init(Files.createTempDirectory("crash").toFile())
        repeat(15) { i -> CrashLog.record("后台任务出错", "第 $i 条", (1..100).joinToString("\n") { "line $it" }) }
        val entries = CrashLog.entries()
        entries shouldHaveSize 10
        entries.first().summary shouldBe "第 5 条"
        entries.last().summary shouldBe "第 14 条"
        entries.last().detail shouldHaveSize 40
    }

    "a multi-line message stays one summary line" {
        CrashLog.init(Files.createTempDirectory("crash").toFile())
        CrashLog.record("系统记录：无响应（ANR）", "第一行\n第二行", "detail")
        CrashLog.entries().single().summary shouldBe "第一行 第二行"
    }
})
