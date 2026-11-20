package com.acgcompass.feature.detail

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.domain.ai.AiRunOptions
import com.acgcompass.domain.ai.RadarCachePolicy
import com.acgcompass.domain.ai.RadarSummarySource
import com.acgcompass.domain.ai.SpoilerLevel
import com.acgcompass.domain.ai.SpoilerRadarOutput
import com.acgcompass.domain.ai.SpoilerRadarResult
import com.acgcompass.domain.model.AiResult
import com.acgcompass.domain.model.AiTaskType
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.AiRepository
import com.acgcompass.domain.repository.DetailInteractionRepository
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.usecase.GenerateSpoilerRadarUseCase
import com.acgcompass.domain.usecase.RadarOutcome
import com.acgcompass.domain.usecase.RadarRequest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 无剧透评价雷达：输入指纹和提示词版本都没变时直接用缓存；否则带上社区评分、真实他人短评调用
 * [GenerateSpoilerRadarUseCase]（已配置 AI 时成本视为已确认，未配置时回退本地规则），结果写回缓存。
 */
internal class DetailRadarLoader(
    private val workRepository: WorkRepository,
    private val detailInteractions: DetailInteractionRepository,
    private val aiRepository: AiRepository,
    private val generateSpoilerRadar: GenerateSpoilerRadarUseCase,
    private val json: Json,
) {
    /**
     * @param myComment 自己的短评（隐私开关允许时才进 AI 请求）。
     * @param onAiFallback 已配置 AI 却回退到本地规则时的说明，交给页面提示。
     * @param publish 拿到结果立即交给页面；写缓存在之后进行。
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend fun load(
        work: Work,
        myComment: String?,
        forceRefresh: Boolean,
        onAiFallback: (String) -> Unit,
        publish: (SpoilerRadarResult) -> Unit,
    ) {
        val stableRequest = RadarRequest(
            workId = work.id,
            title = work.titles.canonical,
            summary = work.summary.orEmpty(),
            mediaType = work.mediaType.name,
            tags = work.tags.map { it.name },
            userComments = listOfNotNull(myComment?.takeIf { it.isNotBlank() }),
            // 隐私开关：未允许时，评论只参与本地规则版雷达，不进 AI 请求。
            allowReviewsToAi = detailInteractions.isAiReviewAnalysisAllowed(),
        )
        val fingerprint = RadarCachePolicy.fingerprint(stableRequest)
        if (!forceRefresh) {
            val cached = (aiRepository.getCached(work.id, AiTaskType.SPOILER_RADAR) as? AppResult.Success)?.data
            if (
                cached?.inputFingerprint == fingerprint &&
                cached.promptVersion == RadarCachePolicy.PROMPT_VERSION
            ) {
                val output = runCatchingCancellable {
                    json.decodeFromString<SpoilerRadarOutput>(
                        cached.payloadJson,
                    )
                }.getOrNull()
                if (output != null) {
                    publish(
                        SpoilerRadarResult(
                            workId = work.id,
                            output = output,
                            generator = cached.generator,
                            requestedLevel = SpoilerLevel.NO_SPOILER,
                            effectiveLevel = SpoilerLevel.NO_SPOILER,
                            summarySources = cached.dataSources.mapNotNull { label ->
                                RadarSummarySource.entries.firstOrNull { it.label == label }
                            },
                            generatedAt = cached.generatedAt,
                        ),
                    )
                    return
                }
            }
        }
        // 社区评分信号（仅读本地缓存，不联网），让 AI 校准口碑判断。
        val communitySignal = runCatchingCancellable {
            val agg = workRepository.aggregateRatingsCached(work.id)
            val scored = agg.perSource.entries.mapNotNull { (src, entry) ->
                val s = entry?.score ?: return@mapNotNull null
                if (s <= 0f) return@mapNotNull null
                val ten = when (src) {
                    com.acgcompass.domain.model.SourceId.ANILIST,
                    com.acgcompass.domain.model.SourceId.VNDB,
                    -> s / 10f
                    else -> s
                }.coerceIn(0f, 10f)
                ten to (entry.voteCount.coerceAtLeast(0))
            }
            if (scored.isEmpty()) {
                null
            } else {
                scored.map { it.first }.average() to scored.sumOf { it.second }
            }
        }.getOrNull()
        // 真实他人短评作为雷达 publicReviews 输入（AI/规则都更有料）。
        // 用解析出的真实 Bangumi subjectId（主源或链接），避免对非 Bangumi 作品取错条目短评。
        val publicComments = detailInteractions.resolveBangumiSubjectId(work)
            ?.let { detailInteractions.fetchBangumiComments(it, limit = 20) as? AppResult.Success }
            ?.data.orEmpty()
            .filter { it.isNotBlank() }
            .take(15)
        val request = stableRequest.copy(
            publicReviews = publicComments,
            communityScore = communitySignal?.first,
            communityVotes = communitySignal?.second,
        )
        // 雷达走 AI 驱动——已配置 AI 时直接以「成本已确认」运行，产出真实无剧透分析；
        // 未配置 AI 时回退本地规则（占位 + 引导配置 AI）。决策助手/评论摘要均消费该结果。
        val aiConfigured = detailInteractions.isAiConfigured()
        val generated = when (
            val outcome = generateSpoilerRadar(request, AiRunOptions(confirmed = aiConfigured))
        ) {
            is RadarOutcome.Ready -> {
                // 已配置 AI 却回退到规则时，把原因暴露给用户（便于诊断 AI 为何没跑成）。
                if (aiConfigured && outcome.aiFallbackReason != null) {
                    onAiFallback(
                        "AI 雷达本次回退本地规则：" + outcome.aiFallbackReason.cause +
                            "（" + outcome.aiFallbackReason.nextStep + "）",
                    )
                }
                outcome.result
            }
            is RadarOutcome.NeedsCostConfirmation -> generateSpoilerRadar.local(request)
        }
        publish(generated)
        runCatchingCancellable {
            aiRepository.put(
                AiResult(
                    id = "spoiler-radar:${work.id}",
                    workId = work.id,
                    taskType = AiTaskType.SPOILER_RADAR,
                    generator = generated.generator,
                    payloadJson = json.encodeToString(generated.output),
                    confidence = generated.confidence,
                    dataSources = generated.summarySources.map { it.label },
                    generatedAt = generated.generatedAt,
                    inputFingerprint = fingerprint,
                    promptVersion = RadarCachePolicy.PROMPT_VERSION,
                ),
            )
        }
    }
}
