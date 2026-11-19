package com.acgcompass.feature.tastecode

import com.acgcompass.domain.tastecode.RepresentativeReason
import com.acgcompass.domain.tastecode.RepresentativeWork
import com.acgcompass.domain.tastecode.ScoringStyle
import com.acgcompass.domain.tastecode.TasteCode
import com.acgcompass.domain.tastecode.TasteCompatibility
import com.acgcompass.domain.tastecode.WeightedTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompatibilityInsightsTest {
    private fun code(
        style: ScoringStyle,
        eras: List<Float>,
        dims: Map<String, Float>,
        works: List<RepresentativeWork> = emptyList(),
        divergence: List<WeightedTag> = emptyList(),
    ) = TasteCode(
        generatedOnDay = 1,
        algorithmVersion = 1,
        style = style,
        dimensions = dims,
        liked = listOf(WeightedTag("日常", 1f, "topic")),
        divergence = divergence,
        eraShares = eras,
        works = works,
    )

    private fun w(id: Int, rating: Int) = RepresentativeWork(id, rating, RepresentativeReason.TOP_RATED)

    private val old = listOf(0f, 0.3f, 0.4f, 0.3f, 0f, 0f, 0f, 0f)
    private val new = listOf(0f, 0f, 0f, 0f, 0f, 0.2f, 0.5f, 0.3f)

    @Test
    fun describesTheBiggestDifferences() {
        val mine = code(
            ScoringStyle(300, 8f, 1f, 0.8f, 0.5f),
            old,
            mapOf("topic" to 1f, "cv" to 0.2f),
            works = listOf(w(1, 9), w(2, 9), w(3, 4)),
            divergence = listOf(WeightedTag("百合", 0.5f), WeightedTag("后宫", -0.6f)),
        )
        val other = code(
            ScoringStyle(50, 6.5f, 2.2f, -0.5f, 1.3f),
            new,
            mapOf("topic" to 0.3f, "cv" to 1f),
            works = listOf(w(1, 9), w(2, 8), w(3, 9)),
            divergence = listOf(WeightedTag("百合", 0.4f), WeightedTag("后宫", -0.3f)),
        )
        val insights = CompatibilityInsights.of(mine, other, TasteCompatibility.compare(mine, other), "小林")
            .associate { it.title to it.body }
        assertTrue(insights.getValue("打分松紧").contains("你给的分平均比小林高 1.5 分"))
        assertTrue(insights.getValue("爱憎程度").contains("小林是「爱憎分明」"))
        assertTrue(insights.getValue("跟不跟口碑").contains("小林很看口碑"))
        assertTrue(insights.getValue("偏爱的年代").startsWith("你看的作品偏早"))
        assertTrue(insights.getValue("阅片量").contains("是小林的 6.0 倍"))
        assertTrue(insights.getValue("看重什么").contains("小林最看重「声优」"))
        assertTrue(insights.getValue("共同作品").contains("有 3 部，其中 2 部"))
        val divergence = insights.getValue("共同的小众偏好")
        assertTrue(divergence.contains("「百合」的作品，你们都比大众打分更高"))
        assertTrue(divergence.contains("对「后宫」，你们都比大众更挑剔"))
    }

    @Test
    fun similarPeopleGetSharedWording() {
        val style = ScoringStyle(100, 7f, 1.5f, 0f, 1f)
        val a = code(style, new, mapOf("topic" to 1f))
        val insights = CompatibilityInsights.of(
            a,
            a,
            TasteCompatibility.compare(a, a),
            "Ta",
        ).associate { it.title to it.body }
        assertTrue(insights.getValue("打分松紧").contains("打分的松紧差不多"))
        assertEquals("你们都和大众口碑大体同步。", insights.getValue("跟不跟口碑"))
        assertTrue(insights.getValue("看重什么").startsWith("你们最看重的都是「题材」"))
        assertTrue("雷区" !in insights)
    }
}
