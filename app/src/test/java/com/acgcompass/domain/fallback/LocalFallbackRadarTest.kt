package com.acgcompass.domain.fallback

import com.acgcompass.data.remote.ai.prompt.SpoilerGuard
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import io.kotest.matchers.string.shouldNotContain

/**
 * 。
 *
 * 覆盖：丰富输入时所有维度非空、稀疏输入 → 低置信、产出文本经 scrub 后不残留被禁剧透 token。
 */
class LocalFallbackRadarTest : StringSpec({

    "丰富输入：所有维度均非空且置信度落在 0..0.6" {
        val input = RadarInput(
            title = "测试作品",
            tags = listOf("热血", "战斗", "作画精良", "致郁", "节奏慢", "悬疑", "魔改"),
            reviewSnippets = listOf("配乐很赞", "后段有点拖沓", "画面很燃", "情节压抑"),
            sourceLabels = listOf("Bangumi 标签"),
        )

        val out = LocalFallbackRadar.generate(input)

        out.overallImpression.shouldNotBeEmpty()
        out.pros.shouldNotBeEmpty()
        out.controversies.shouldNotBeEmpty()
        out.pitfalls.shouldNotBeEmpty()
        out.suitableFor.shouldNotBeEmpty()
        out.notSuitableFor.shouldNotBeEmpty()
        out.watchTiming.shouldNotBeEmpty()
        out.sources.shouldNotBeEmpty()
        (out.confidence in 0f..0.6f) shouldBe true
    }

    "丰富输入命中关键词：优点/争议/雷点/适合/不适合人群均产出真实启发式条目" {
        val input = RadarInput(
            tags = listOf("热血", "作画", "致郁", "拖沓"),
            reviewSnippets = listOf("音乐很好", "节奏慢"),
        )

        val out = LocalFallbackRadar.generate(input)

        out.pros shouldContain "作画与画面表现获得好评"
        out.pitfalls shouldContain "情绪偏沉重压抑，心情不佳时慎看"
        out.controversies shouldContain "节奏存在拖沓争议"
        out.suitableFor shouldContain "喜欢热血 / 战斗题材的观众"
        out.notSuitableFor shouldContain "想找轻松解压内容的观众"
    }

    "稀疏输入 → 低置信度（< 0.3），但仍填充所有维度（不编造）" {
        val sparse = RadarInput(tags = listOf("治愈"))

        val out = LocalFallbackRadar.generate(sparse)

        out.confidence.shouldBeLessThan(0.3f)
        out.overallImpression.shouldNotBeEmpty()
        out.pros.shouldNotBeEmpty()
        out.controversies.shouldNotBeEmpty()
        out.pitfalls.shouldNotBeEmpty()
        out.suitableFor.shouldNotBeEmpty()
        out.notSuitableFor.shouldNotBeEmpty()
        out.watchTiming.shouldNotBeEmpty()
        out.sources.shouldNotBeEmpty()
    }

    "完全空输入 → 置信度为 0 且整体印象诚实说明资料不足" {
        val out = LocalFallbackRadar.generate(RadarInput())

        out.confidence shouldBe 0f
        out.overallImpression shouldContain "资料有限"
        out.sources.shouldNotBeEmpty()
    }

    "丰富输入比稀疏输入置信度更高（信号越多越自信）" {
        val sparse = LocalFallbackRadar.generate(RadarInput(tags = listOf("热血")))
        val rich = LocalFallbackRadar.generate(
            RadarInput(
                tags = listOf("热血", "作画", "悬疑", "致郁"),
                reviewSnippets = listOf("音乐很赞", "节奏慢", "画面燃"),
            ),
        )
        rich.confidence.shouldBeGreaterThan(sparse.confidence)
    }

    "提供 scrub 时：标签中夹带的被禁剧透 token 在所有输出文本中被抽象化" {
        // 标签「大反转」「神结局」含被禁 token，会被拼进整体印象；经 SpoilerGuard 过滤应被隐去。
        val input = RadarInput(
            title = "大反转神结局",
            tags = listOf("大反转", "神结局", "热血"),
            reviewSnippets = listOf("中后期有重要转折"),
        )

        val out = LocalFallbackRadar.generate(input, scrub = SpoilerGuard::scrubText)

        val allText = buildList {
            add(out.overallImpression)
            add(out.watchTiming)
            addAll(out.pros)
            addAll(out.controversies)
            addAll(out.pitfalls)
            addAll(out.suitableFor)
            addAll(out.notSuitableFor)
            addAll(out.sources)
        }
        allText.forEach { text ->
            SpoilerGuard.BANNED_TOKENS.forEach { banned ->
                text.lowercase() shouldNotContain banned.lowercase()
            }
        }
        // 被禁 token 应被替换为抽象化文案。
        out.overallImpression shouldContain SpoilerGuard.ABSTRACTION
    }

    "默认恒等 scrub：不主动过滤（领域纯净），原始标签文本得以保留" {
        val input = RadarInput(tags = listOf("大反转", "热血"))

        val out = LocalFallbackRadar.generate(input)

        // 未传 scrub 时为恒等函数，被禁 token 原样出现在整体印象中（由调用方负责过滤）。
        out.overallImpression shouldContain "反转"
    }
})
