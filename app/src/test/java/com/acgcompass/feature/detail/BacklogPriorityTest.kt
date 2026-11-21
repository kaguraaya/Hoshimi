package com.acgcompass.feature.detail

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class BacklogPriorityTest : StringSpec({
    fun taste(fraction: Float, rank: Int?, uncertain: Boolean = false) = TasteMatchUiModel.Available(
        percentText = "${(fraction * 100).toInt()}%",
        qualitativeText = "",
        fraction = fraction,
        reason = "",
        rankPercent = rank,
        uncertain = uncertain,
    )

    fun consensus(priority: Float) = ConsensusUiModel.Available(
        stability = ConsensusMetric("", "", "", 0.5f),
        controversy = ConsensusMetric("", "", "", 0.5f),
        priority = ConsensusMetric("", "", "", priority),
    )

    val short = CompletionCostUiModel(available = true, bucketLabel = "今晚可看完", detailText = "")

    "with a rating model the personal rank counts once and community does not add on top" {
        val factors = priorityFactors(taste(0.9f, rank = 30), consensus(1f), short)

        factors.map { it.up } shouldBe listOf("预计评分在你看过的作品里偏高", "篇幅短易补完")
    }

    "without a rating model the profile match and community fill in" {
        val factors = priorityFactors(taste(0.8f, rank = null), consensus(0.9f), short)

        factors.map { it.up } shouldBe listOf("口味匹配较高", "社区评价较高", "篇幅短易补完")
    }

    "a wide prediction interval never yields high priority" {
        val (sure, _) = computeBacklogPriority(taste(0.95f, rank = 95), consensus(1f), short, emptyList())
        val (unsure, reason) = computeBacklogPriority(
            taste(0.95f, rank = 95, uncertain = true),
            consensus(1f),
            short,
            emptyList(),
        )

        sure shouldBe "高"
        unsure shouldBe "中"
        reason!! shouldContain "把握不大"
    }
})
