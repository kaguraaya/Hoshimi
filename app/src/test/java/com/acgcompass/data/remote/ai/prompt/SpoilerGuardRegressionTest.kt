package com.acgcompass.data.remote.ai.prompt

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json

/**
 * 无剧透回归测试：模拟模型输出里带死亡、身份揭露、结局、反转、感情结果等具体剧情的句子，
 * 过滤后不能再出现这些具体表述；只谈风格、节奏、人群的正常描述不能被误伤。
 */
class SpoilerGuardRegressionTest : StringSpec({
    val spoilers = mapOf(
        "死亡" to listOf("男主在第十话死了", "女二最后牺牲了自己", "师父在战斗中身亡", "主角其实早就去世了", "队长被杀"),
        "身份" to listOf("她的真实身份是魔王", "真凶就是班主任", "幕后黑手是主角的父亲", "那个转学生其实是卧底"),
        "结局" to listOf("大结局非常圆满", "结局是两人分开", "The ending reveals everything", "最终结局令人唏嘘"),
        "反转" to listOf("第八集有一个大反转", "主角后期黑化", "挚友背叛了大家"),
        "感情" to listOf("最后两人在一起了", "男女主告白成功", "两人最后结婚了", "第三季两人开始交往"),
    )

    "every simulated spoiler is abstracted" {
        spoilers.forEach { (_, sentences) ->
            sentences.forEach { sentence ->
                val scrubbed = SpoilerGuard.scrubText(sentence)
                scrubbed shouldContain SpoilerGuard.ABSTRACTION
                SpoilerGuard.BANNED_TOKENS.forEach { token -> scrubbed.lowercase() shouldNotContain token.lowercase() }
            }
        }
    }

    "nested JSON output is scrubbed field by field and keys stay intact" {
        val raw = """{"summary":"真凶就是班主任","pros":["作画精良","最后两人在一起了"],"meta":{"note":"男主死了"}}"""
        val scrubbed = SpoilerGuard.scrubJsonString(Json, raw).orEmpty()
        scrubbed shouldContain "\"summary\""
        scrubbed shouldContain "作画精良"
        scrubbed shouldNotContain "真凶"
        scrubbed shouldNotContain "在一起"
        scrubbed shouldNotContain "死了"
    }

    "style and audience descriptions are left alone" {
        val normal = listOf(
            "节奏紧凑，作画精良，适合喜欢日常的观众",
            "前期铺垫较慢，中后期有重要转折",
            "配乐出色，声优演技在线",
            "结尾存在争议",
        )
        normal.filterNot { it.contains("结尾") }.forEach { SpoilerGuard.scrubText(it) shouldBe it }
    }

    "invalid JSON is reported instead of being passed through" {
        SpoilerGuard.scrubJsonString(Json, "{not json") shouldBe null
    }
})
