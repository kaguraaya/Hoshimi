package com.acgcompass.feature.detail

import com.acgcompass.domain.ai.RadarSummarySource
import com.acgcompass.domain.ai.SpoilerLevel
import com.acgcompass.domain.ai.SpoilerRadarOutput
import com.acgcompass.domain.ai.SpoilerRadarResult
import com.acgcompass.domain.model.AiGenerator
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * 同一份无剧透分析只在「评论摘要」里完整列好评点、争议点和需留意；决策助手只留本地信号并指过去，
 * 总体印象只在雷达卡片里显示。
 */
class DetailReviewMergeTest : StringSpec({
    val radar = SpoilerRadarResult(
        workId = "1",
        output = SpoilerRadarOutput(
            overallImpression = "校园搞笑日常",
            pros = listOf("角色人设丰富"),
            controversies = listOf("段子尺度较大"),
            pitfalls = listOf("性暗示较多"),
            watchTiming = "适合放松时看",
        ),
        generator = AiGenerator.AI,
        requestedLevel = SpoilerLevel.NO_SPOILER,
        effectiveLevel = SpoilerLevel.NO_SPOILER,
        summarySources = listOf(RadarSummarySource.entries.first()),
        generatedAt = 0L,
    )
    val work =
        Work(id = "1", titles = Titles(canonical = "作品"), mediaType = MediaType.ANIME, primarySource = SourceId.BANGUMI)

    "decision helper keeps local signals and points to the review summary" {
        val decision = work.toDecisionUiModel(
            consensus = ConsensusUiModel.Insufficient,
            tasteMatch = TasteMatchUiModel.Unavailable("暂无"),
            completionCost = CompletionCostUiModel(available = false, bucketLabel = "", detailText = ""),
            radar = radar,
        )
        decision.recommendReasons shouldNotContain "角色人设丰富"
        decision.recommendReasons shouldContain REVIEW_PROS_POINTER
        decision.notRecommendReasons shouldNotContain "段子尺度较大"
        decision.notRecommendReasons shouldNotContain "性暗示较多"
        decision.notRecommendReasons shouldContain REVIEW_CONS_POINTER
    }

    "review summary lists every point once and leaves the overall impression to the radar card" {
        val summary = reviewSummary(radar)
        summary shouldContain "角色人设丰富"
        summary shouldContain "段子尺度较大"
        summary shouldContain "需留意"
        summary shouldContain "性暗示较多"
        summary shouldNotContain "校园搞笑日常"
        radar.toRadarSummary() shouldContain "校园搞笑日常"
    }
})
