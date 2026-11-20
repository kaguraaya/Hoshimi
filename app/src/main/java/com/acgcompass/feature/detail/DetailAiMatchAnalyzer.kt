package com.acgcompass.feature.detail

import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.AiRunOptions
import com.acgcompass.domain.ai.AiRunResult
import com.acgcompass.domain.ai.AiTask
import com.acgcompass.domain.ai.TasteMatchOutput
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.TagBucket
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.DetailInteractionRepository
import com.acgcompass.domain.taste.TasteAnalysisController
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * 「AI 分析匹配度」：把作品资料、社区评分、口味画像、近期与历史高低分评分和本地分歧模型的证据组装成输入，
 * 交给 AI 并把结果转成界面状态。隐私开关关闭时不带任何短评文本。
 */
internal class DetailAiMatchAnalyzer(
    private val detailInteractions: DetailInteractionRepository,
    private val aiEngine: AiEngine,
    private val tasteAnalysis: TasteAnalysisController,
) {
    suspend fun analyze(work: Work, profile: TasteProfile?, community: RatingAggregate?): AiMatchUi {
        val highTags = profile?.tagStats
            ?.filter { it.bucket == TagBucket.HIGH_SCORE }
            ?.map { it.tagName }
            ?.take(20)
            .orEmpty()
        val lowTags = profile?.tagStats
            ?.filter { it.bucket == TagBucket.LOW_SCORE }
            ?.map { it.tagName }
            ?.take(20)
            .orEmpty()
        // 纳入近期评分作品 + 历史最高/最低分锚点，比仅用标签更具参考价值。
        val allRated = runCatchingCancellable { detailInteractions.ratedWorkContexts() }.getOrDefault(emptyList())
        val recentRated = allRated.sortedByDescending { it.updatedAt }.take(20)
        // 口味锚点——历史最高分（最喜欢）与最低分（最不喜欢），各取前若干。
        val topRated = allRated.sortedByDescending { it.rating }.take(5)
        val lowRated = allRated.filter { it.rating <= 6 }.sortedBy { it.rating }.take(5)
        // 隐私开关「允许把短评发给 AI」：关闭（默认）时，发给 AI 的内容里不带任何短评文本。
        val allowComments = detailInteractions.isAiReviewAnalysisAllowed()
        val lineOf: (com.acgcompass.domain.detail.RatedWorkContext) -> String = { context ->
            buildString {
                append(context.title).append(" · ").append(context.rating).append("分")
                context.comment?.takeIf { allowComments && it.isNotBlank() }?.let {
                    append("（短评：").append(it.take(40)).append("）")
                }
            }
        }
        val ratedLines = recentRated.map(lineOf)
        val topLines = topRated.map(lineOf)
        val lowLines = lowRated.map(lineOf)
        // 本地个人分歧模型给出的证据：大众基线 / 预计评分 / 分歧来源、题材相近的已评分作品、
        // 与大众分歧最大的评分。让 AI 在有数据支撑的先验上做归因，而不是只看一堆标签凭感觉打分。
        val divergenceEvidence = runCatchingCancellable {
            buildDivergenceEvidence(
                work,
                allRated,
                allowComments,
            )
        }.getOrDefault("")
        val content = buildString {
            append("作品：").append(work.titles.canonical).append('\n')
            community?.let { agg ->
                agg.perSource[com.acgcompass.domain.model.SourceId.BANGUMI]?.let { entry ->
                    append("Bangumi 社区评分：").append(entry.score)
                    entry.voteCount?.let { append("（").append(it).append(" 人评分）") }
                    append('\n')
                }
            }
            work.titles.ja?.let { append("原名：").append(it).append('\n') }
            append("类型：").append(work.mediaType.name).append('\n')
            work.year?.let { append("年份：").append(it).append('\n') }
            val workTags = work.tags.map { it.name }
            if (workTags.isNotEmpty()) append("作品标签：").append(workTags.joinToString("、")).append('\n')
            work.summary?.takeIf { it.isNotBlank() }?.let {
                append("简介：").append(it.take(400)).append('\n')
            }
            if (topLines.isNotEmpty()) {
                append("用户历史高评分作品：\n").append(topLines.joinToString("\n")).append('\n')
            }
            if (lowLines.isNotEmpty()) {
                append("用户历史低评分作品：\n").append(lowLines.joinToString("\n")).append('\n')
            }
            if (ratedLines.isNotEmpty()) {
                append("用户近期评分作品（标题、评分、短评）：\n")
                append(ratedLines.joinToString("\n")).append('\n')
            }
            append(divergenceEvidence)
            if (highTags.isNotEmpty()) append("高评分作品常见标签：").append(highTags.joinToString("、")).append('\n')
            if (lowTags.isNotEmpty()) append("低评分作品常见标签：").append(lowTags.joinToString("、")).append('\n')
            profile?.avgScore?.takeIf { it > 0f }?.let { append("用户平均分：").append("%.1f".format(it)).append('\n') }
        }
        val dataSources = buildList {
            add("作品资料")
            if (recentRated.isNotEmpty()) add("近期评分作品")
            if (profile != null) add("口味画像")
            if (community != null) add("社区评分")
        }

        val result = runCatchingCancellable {
            aiEngine.run(
                AiTask.TasteMatch(workId = work.id, content = content, dataSources = dataSources),
                AiRunOptions(confirmed = true),
            )
        }.getOrElse { AiRunResult.Failure(com.acgcompass.core.common.AppError.Server()) }

        return toMatchUi(result, dataSources)
    }

    /** AI 运行结果 → 界面状态；结构化结果之外的情况都提示已回退本地模型估计。 */
    private fun toMatchUi(result: AiRunResult<TasteMatchOutput>, dataSources: List<String>): AiMatchUi = when (result) {
        is AiRunResult.Success -> {
            val out = result.payload
            val fraction = (out.matchScore.coerceIn(0, 100)) / 100f
            AiMatchUi.Result(
                predictedRatingText = out.predictedRating.takeIf { it in 1f..10f }?.let { "%.1f".format(it) },
                divergenceReason = out.divergenceReason.trim().takeIf { it.isNotEmpty() },
                ratingAngles = out.ratingAngles.map { it.trim() }.filter { it.isNotEmpty() }.take(4),
                matchPercentText = "${out.matchScore.coerceIn(0, 100)}%",
                fraction = fraction,
                likedReasons = out.likedReasons,
                riskReasons = out.riskReasons,
                confidenceText = "置信度 ${(out.confidence.coerceIn(0f, 1f) * 100).toInt()}%",
                dataSources = result.result.dataSources.ifEmpty { dataSources },
            )
        }
        is AiRunResult.LowConfidence ->
            AiMatchUi.Error("AI 未返回可靠的结构化结果，当前显示本地口味匹配度")
        is AiRunResult.NotConfigured ->
            AiMatchUi.NotConfigured("未配置 AI，可使用本地模型估计（见上方「口味匹配度」）")
        is AiRunResult.NeedsConfirmation ->
            AiMatchUi.Error("AI 分析需确认成本，已回退本地模型估计")
        is AiRunResult.Failure ->
            AiMatchUi.Error("AI 分析失败（${result.error.nextStep ?: "请稍后重试"}），已回退本地模型估计")
    }

    /**
     * 组装「个人分歧」证据文本（供 AI 口味匹配）。模型未就绪（评分太少）时返回空串——AI 只拿到原有输入。
     */
    private suspend fun buildDivergenceEvidence(
        work: Work,
        rated: List<com.acgcompass.domain.detail.RatedWorkContext>,
        allowComments: Boolean,
    ): String {
        val model = tasteAnalysis.advancedProfile.value?.divergence ?: return ""
        val bangumiId = runCatchingCancellable { detailInteractions.resolveBangumiSubjectId(work) }.getOrNull()
        val match = tasteAnalysis.score(bangumiId?.toString() ?: work.id, null, allowNetwork = false)
        val byId = rated.filter { it.subjectId.isNotBlank() }.associateBy { it.subjectId }
        fun line(e: com.acgcompass.domain.taste.RatedExample): String? {
            val context = byId[e.subjectId] ?: return null
            return buildString {
                append("- ").append(context.title).append("：你 ").append(e.rating).append(" 分，大众基线 ")
                append("%.1f".format(e.baseline)).append("（").append("%+.1f".format(e.divergence)).append("）")
                context.comment?.takeIf { allowComments && it.isNotBlank() }
                    ?.let { append("，短评：").append(it.take(AI_COMMENT_CHARS)) }
            }
        }
        return buildString {
            append("【本地模型】该用户均分 ").append("%.1f".format(model.meanRating))
            append("，比同批作品的社区均分").append(if (model.leniency >= 0) "高 " else "低 ")
            append("%.1f".format(kotlin.math.abs(model.leniency))).append(" 分\n")
            match?.prediction?.let { p ->
                append("大众基线 ").append("%.1f".format(p.baseline)).append("，预计评分 ").append("%.1f".format(p.rating))
                append("（个人分歧 ").append("%+.1f".format(p.divergence)).append("）\n")
                if (p.factors.isNotEmpty()) {
                    append("分歧来源：")
                    append(p.factors.joinToString("、") { "${it.label} ${"%+.1f".format(it.delta)}" }).append('\n')
                }
            }
            val similar = match?.similarRated.orEmpty().mapNotNull(::line)
            if (similar.isNotEmpty()) {
                append("【题材相近的已评分作品】\n").append(similar.joinToString("\n")).append('\n')
            }
            val (above, below) = model.strongestDivergences(AI_DIVERGENCE_EXAMPLES)
            val extremes = (above + below).mapNotNull(::line)
            if (extremes.isNotEmpty()) {
                append("【与大众分歧最大的评分】\n").append(extremes.joinToString("\n")).append('\n')
            }
        }
    }
}

/** AI 输入里每条短评最多带多少字，以及「分歧最大的评分」高低各取几部。 */
private const val AI_COMMENT_CHARS = 60
private const val AI_DIVERGENCE_EXAMPLES = 4
