package com.acgcompass.domain.usecase

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * 导入文本解析器单元测试。聚焦具体分隔符样例与边界；
 * round-trip 全称性质由 覆盖。
 */
class ImportTextParserTest : StringSpec({

    val parser = ImportTextParser()

    fun titles(raw: String): List<String> = parser.parsePastedText(raw).map { it.title }

    "书名号 《》 拆分多个标题" {
        titles("推荐《孤独摇滚》和《葬送的芙莉莲》") shouldContainExactly
            listOf("孤独摇滚", "葬送的芙莉莲")
    }

    "顿号 、 拆分" {
        titles("孤独摇滚、葬送的芙莉莲、链锯人") shouldContainExactly
            listOf("孤独摇滚", "葬送的芙莉莲", "链锯人")
    }

    "半角与全角逗号拆分" {
        titles("A,B，C") shouldContainExactly listOf("A", "B", "C")
    }

    "换行拆分" {
        titles("孤独摇滚\n葬送的芙莉莲\r\n链锯人") shouldContainExactly
            listOf("孤独摇滚", "葬送的芙莉莲", "链锯人")
    }

    "数字加点编号列表剥离前缀" {
        titles("1. 孤独摇滚\n2. 葬送的芙莉莲") shouldContainExactly
            listOf("孤独摇滚", "葬送的芙莉莲")
    }

    "数字加顿号编号列表剥离前缀" {
        titles("1、孤独摇滚\n2、葬送的芙莉莲") shouldContainExactly
            listOf("孤独摇滚", "葬送的芙莉莲")
    }

    "括号编号 (1) 与 （1） 剥离前缀" {
        titles("(1) 孤独摇滚\n（2）葬送的芙莉莲") shouldContainExactly
            listOf("孤独摇滚", "葬送的芙莉莲")
    }

    "带圈数字 ① 剥离前缀" {
        titles("①孤独摇滚\n②葬送的芙莉莲") shouldContainExactly
            listOf("孤独摇滚", "葬送的芙莉莲")
    }

    "丢弃空白条目（dedupe blanks）" {
        titles("孤独摇滚、、\n\n  \n链锯人") shouldContainExactly listOf("孤独摇滚", "链锯人")
    }

    "书名号内的分隔符不拆分（保留为标题一部分）" {
        titles("《我推的孩子，第二季》、链锯人") shouldContainExactly
            listOf("我推的孩子，第二季", "链锯人")
    }

    "数字开头但非编号的标题不被误剥离" {
        titles("2001太空漫游") shouldContainExactly listOf("2001太空漫游")
    }

    "剪贴板与粘贴使用同一解析逻辑" {
        val input = "1. 孤独摇滚、链锯人\n《葬送的芙莉莲》"
        parser.parseClipboardText(input) shouldBe parser.parsePastedText(input)
    }

    "rawSpan 指向原始文本的对应片段" {
        val input = "《孤独摇滚》"
        val candidate = parser.parsePastedText(input).single()
        candidate.title shouldBe "孤独摇滚"
        input.substring(candidate.rawSpan.start, candidate.rawSpan.endExclusive) shouldBe "《孤独摇滚》"
    }

    // region CSV / TXT  --------------------------------------------

    "CSV 第一列为标题、第二列为备注" {
        val result = parser.parseLineBased("孤独摇滚,日常治愈\n链锯人,血腥慎入")
        result.map { it.title } shouldContainExactly listOf("孤独摇滚", "链锯人")
        result.map { it.note } shouldContainExactly listOf("日常治愈", "血腥慎入")
    }

    "TXT 纯标题行无备注" {
        val result = parser.parseLineBased("孤独摇滚\n链锯人")
        result.map { it.title } shouldContainExactly listOf("孤独摇滚", "链锯人")
        result.forEach { it.note.shouldBeNull() }
    }

    "CSV 双引号包裹字段内逗号" {
        val result = parser.parseLineBased("\"我推的孩子,第二季\",\"含,逗号的备注\"")
        result.single().title shouldBe "我推的孩子,第二季"
        result.single().note shouldBe "含,逗号的备注"
    }

    "CSV 跳过空行" {
        val result = parser.parseLineBased("孤独摇滚,治愈\n\n   \n链锯人")
        result.map { it.title } shouldContainExactly listOf("孤独摇滚", "链锯人")
    }

    "CSV 行也剥离编号前缀" {
        val result = parser.parseLineBased("1. 孤独摇滚,治愈")
        result.single().title shouldBe "孤独摇滚"
        result.single().note shouldBe "治愈"
    }

    // endregion

    "OCR 导入入口为预留 stub（无实现）" {
        val stub = object : ScreenshotOcrImporter {
            override val isAvailable = false
            override val permissionRationale = ScreenshotOcrImporter.DEFAULT_PERMISSION_RATIONALE
            override fun recognize(imageBytes: ByteArray): String = throw NotImplementedError()
        }
        stub.isAvailable shouldBe false
        stub.permissionRationale.isNotBlank() shouldBe true
    }
})
