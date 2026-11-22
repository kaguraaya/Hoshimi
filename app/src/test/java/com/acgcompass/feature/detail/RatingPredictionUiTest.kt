package com.acgcompass.feature.detail

import com.acgcompass.domain.taste.RatingPrediction
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class RatingPredictionUiTest : StringSpec({
    fun prediction(low: Double?, high: Double?) = RatingPrediction(
        rating = 7.8,
        baseline = 7.4,
        divergence = 0.4,
        factors = emptyList(),
        community = 7.6,
        userMean = 7.0,
        userStd = 1.2,
        low = low,
        high = high,
    )

    "the prediction block shows the interval when the model gives one" {
        prediction(6.84, 8.91).toUi().intervalText shouldBe "大概率在 6.8–8.9 分之间"
    }

    "no interval line for the fixed-weight model" {
        prediction(null, null).toUi().intervalText.shouldBeNull()
    }

    "with a distribution every number on the block comes from it" {
        val atLeast = listOf(0.9939, 0.9671, 0.8517, 0.5572, 0.1304)
        val summary = com.acgcompass.domain.taste.RatingChances.summarize(atLeast, 4.0)
        val ui = prediction(7.7, 9.9).copy(
            rating = summary.mean,
            atLeast = atLeast,
            highChance = atLeast[3],
            summary = summary,
        ).toUi()
        ui.predictedText shouldBe "9"
        ui.meanText shouldBe "平均 8.5"
        ui.likelyText shouldBe "最可能 9 分（43%）"
        ui.intervalText shouldBe "大概率在 7–10 分之间"
        ui.highChanceText shouldBe "很可能打 9 分以上（约 55%）"
        ui.chanceBuckets.map { it.percent } shouldBe listOf(13, 43, 29, 12, 3)
        // 「这几个数怎么看」用这部作品自己的数说明四种读法。
        ui.readingGuide.size shouldBe 4
        ui.readingGuide[0] shouldBe "预计你会打 9 分：落在中间的分数，打得比它高、比它低的可能都不超过一半。"
        ui.readingGuide[1].startsWith("平均 8.5 分：") shouldBe true
        ui.readingGuide[2].startsWith("最可能 9 分（43%）：") shouldBe true
    }

    "without a distribution there is nothing to explain" {
        prediction(7.0, 9.0).toUi().readingGuide shouldBe emptyList()
    }

    "a clear disagreement is explained with the evidence the models actually have" {
        // 口味很对（匹配度 73）但预计评分偏低、社区评分很低：说出社区分，不编原因。
        val low = prediction(3.3, 5.5).copy(rating = 4.4, community = 4.1, userMean = 7.4, userStd = 1.2)
        val note = disagreementNote(73, low, circleZ = 1.5)
        note.contains("社区评分只有 4.1") shouldBe true
        note.contains("常看的那类") shouldBe true
        note.contains("把握不大") shouldBe true
        disagreementNote(73, prediction(6.8, 8.9), circleZ = 1.5) shouldBe ""
    }
})
