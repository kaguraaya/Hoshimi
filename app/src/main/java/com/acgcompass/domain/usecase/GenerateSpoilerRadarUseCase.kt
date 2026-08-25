package com.acgcompass.domain.usecase

import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.AiRunOptions
import com.acgcompass.domain.ai.AiRunResult
import com.acgcompass.domain.ai.AiTask
import com.acgcompass.domain.ai.CostRange
import com.acgcompass.domain.ai.RadarSummarySource
import com.acgcompass.domain.ai.SpoilerLevel
import com.acgcompass.domain.ai.SpoilerRadarOutput
import com.acgcompass.domain.ai.SpoilerRadarResult
import com.acgcompass.domain.ai.SpoilerScrubber
import com.acgcompass.domain.fallback.LocalFallbackRadar
import com.acgcompass.domain.fallback.RadarInput
import com.acgcompass.domain.model.AiGenerator

/**
 * 防剧透雷达生成请求（ 的输入）。
 *
 * 区分用户短评与公共 Reviews 以支撑来源标注。所有信号均可空 / 为空，资料越稀疏，
 * 生成结果置信度越低、不编造。
 *
 * @property workId       规范化作品 id。
 * @property title        作品标题（仅用于点缀整体印象文案）。
 * @property tags         作品标签 / 题材关键词（→ 标签统计来源）。
 * @property userComments 用户个人短评（→ 用户短评来源）。
 * @property publicReviews 公共评论片段（→ 公共 Reviews 来源）。
 * @property spoilerLevel 请求的剧透等级；当前仅 [SpoilerLevel.NO_SPOILER] 生效。
 */
data class RadarRequest(
    val workId: String,
    val title: String = "",
    val summary: String = "",
    val tags: List<String> = emptyList(),
    val userComments: List<String> = emptyList(),
    val publicReviews: List<String> = emptyList(),
    /**
     * 是否允许把评论内容（[userComments] / [publicReviews]）发给 AI。对应「隐私与数据 → 允许 AI 分析评论」；
     * 为 false 时评论只用于本地规则版雷达，AI 请求里不带任何评论文本。
     */
    val allowReviewsToAi: Boolean = true,
    /** 社区平均分（0~10 归一化口径），用于让 AI 校准整体口碑判断；缺失为 null。 */
    val communityScore: Double? = null,
    /** 社区评分人数，反映样本量 / 关注度；缺失为 null。 */
    val communityVotes: Int? = null,
    /** 题材 / 类型（如 TV、剧场版、催泪向），用于 AI 判断观看时机与适合人群。 */
    val mediaType: String = "",
    val spoilerLevel: SpoilerLevel = SpoilerLevel.DEFAULT,
)

/**
 * 雷达生成结果（[GenerateSpoilerRadarUseCase] 的输出）。
 */
sealed interface RadarOutcome {

    /** 已生成可展示结果（AI 增强或规则回退）。 */
    data class Ready(
        val result: SpoilerRadarResult,
        /**
         * 当请求期望 AI 但最终回退到本地规则时，携带导致回退的原因（未配置 / 低置信 / 网络失败等），
         * 供 UI 诊断「为何仍是规则生成」。AI 成功或本不期望 AI 时为 `null`。
         */
        val aiFallbackReason: com.acgcompass.core.common.AppError? = null,
    ) : RadarOutcome

    /**
     * 需要先确认 AI 调用成本。调用方应展示 [estimate]，用户确认后以
     * `options.copy(confirmed = true)` 重新调用。规则回退路径永远不会返回此结果。
     */
    data class NeedsCostConfirmation(val estimate: CostRange) : RadarOutcome
}

/**
 * 防剧透雷达生成用例。
 *
 * 编排「AI 增强」与「本地规则回退」两条路径，并产出剧透等级与来源标注：
 *
 * 1. **AI 增强**：组装无剧透任务调用 [AiEngine]；成功则标注 `generator = AI`
 *    并把 [RadarSummarySource.AI] 计入来源。
 * 2. **规则回退**：当 AI 未配置 / 失败 / 仅得低置信结果时，回退到
 *    [LocalFallbackRadar]，标注 `generator = RULE`，保证雷达始终可展示且不编造。
 * 3. **强制无剧透**：任何请求的剧透等级都会被收敛到唯一已实现的
 *    [SpoilerLevel.NO_SPOILER]；[MILD] / [FULL] 为预留等级，当前安全降级，绝不提升剧透度。
 * 4. **剧透净化**：规则回退路径的产出文本经 [SpoilerScrubber] 再过滤一次（AI 路径由引擎内部净化），
 *    确保展示文本不残留被禁剧透 token。
 * 5. **成本确认**：当 [AiEngine] 因成本未确认而要求确认时，透传
 *    [RadarOutcome.NeedsCostConfirmation]，不擅自发起请求。
 *
 * 本类无 Android / IO 直接依赖（仅依赖领域抽象），便于单元测试；[clock] 可注入以固定生成时间。
 *
 */
class GenerateSpoilerRadarUseCase(
    private val aiEngine: AiEngine,
    private val scrubber: SpoilerScrubber = SpoilerScrubber.NONE,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 生成防剧透雷达。
     *
     * @param request 雷达输入信号与请求剧透等级。
     * @param options AI 运行选项（成本确认 / 仅分析摘要等）。
     */
    suspend operator fun invoke(request: RadarRequest, options: AiRunOptions = AiRunOptions()): RadarOutcome {
        // 强制收敛到唯一已实现的无剧透等级；预留等级安全降级。
        val effectiveLevel = enforceImplementedLevel(request.spoilerLevel)
        val inputSources = inputSources(request)

        val task = AiTask.SpoilerRadar(
            workId = request.workId,
            content = buildAiContent(request),
            dataSources = inputSources.map { it.label },
        )

        return when (val run = aiEngine.run(task, options)) {
            is AiRunResult.Success ->
                RadarOutcome.Ready(
                    aiResult(
                        request = request,
                        effectiveLevel = effectiveLevel,
                        inputSources = inputSources,
                        payload = run.payload,
                        generatedAt = run.result.generatedAt,
                    ),
                )

            // 成本未确认：透传以便 UI 展示成本并请求确认。
            is AiRunResult.NeedsConfirmation -> RadarOutcome.NeedsCostConfirmation(run.estimate)

            // 未配置 / 低置信 / 其它失败：回退到本地规则版，保证雷达始终可展示。
            is AiRunResult.NotConfigured,
            is AiRunResult.LowConfidence,
            is AiRunResult.Failure,
            -> RadarOutcome.Ready(
                ruleResult(
                    request = request,
                    effectiveLevel = effectiveLevel,
                    inputSources = inputSources,
                ),
                aiFallbackReason = when (run) {
                    is AiRunResult.LowConfidence -> run.error
                    is AiRunResult.Failure -> run.error
                    else -> null
                },
            )
        }
    }

    /** 仅放行已实现的剧透等级；预留等级（[SpoilerLevel.MILD]/[FULL]）安全降级为默认。 */
    private fun enforceImplementedLevel(requested: SpoilerLevel): SpoilerLevel =
        if (requested.implemented) requested else SpoilerLevel.DEFAULT

    /**
     * 仅本地规则生成（不调用 AI、不产生成本、绝不返回 null），供展示层「始终有内容」兜底——
     * 当 AI 需成本确认或未配置时用它填充决策助手 / 评论摘要，AI 增强仍可由用户显式触发。
     */
    fun local(request: RadarRequest): SpoilerRadarResult = ruleResult(
        request = request,
        effectiveLevel = enforceImplementedLevel(request.spoilerLevel),
        inputSources = inputSources(request),
    )

    /** 由非空信号推导摘要来源标注；不含 AI（AI 由生成路径单独追加）。 */
    private fun inputSources(request: RadarRequest): List<RadarSummarySource> = buildList {
        if (request.userComments.any { it.isNotBlank() }) add(RadarSummarySource.USER_COMMENTS)
        if (request.publicReviews.any { it.isNotBlank() }) add(RadarSummarySource.PUBLIC_REVIEWS)
        if (request.tags.any { it.isNotBlank() }) add(RadarSummarySource.TAG_STATS)
    }

    /** 组装 AI 任务输入文本；剧透保护由引擎系统提示词强制，此处仅整理可得信号。 */
    private fun buildAiContent(request: RadarRequest): String = buildString {
        request.title.trim().takeIf { it.isNotEmpty() }?.let { appendLine("标题：$it") }
        request.mediaType.trim().takeIf { it.isNotEmpty() }?.let { appendLine("类型：$it") }
        // 纳入作品简介，让 AI 即便对小众番也能基于剧情梗概给出整体印象（仍受无剧透系统提示约束）。
        request.summary.trim().takeIf { it.isNotEmpty() }
            ?.let { appendLine("作品简介：\n${it.take(1200)}") }
        // 纳入社区评分与样本量，帮助 AI 校准口碑强弱与争议程度。
        request.communityScore?.takeIf { it > 0 }?.let { score ->
            val votes = request.communityVotes?.takeIf { it > 0 }
            appendLine("社区评分：%.1f/10".format(score) + (votes?.let { "（$it 人评价）" } ?: ""))
        }
        cleanList(request.tags).takeIf { it.isNotEmpty() }
            ?.let { appendLine("标签：${it.joinToString("、")}") }
        if (request.allowReviewsToAi) {
            cleanList(request.userComments).takeIf { it.isNotEmpty() }
                ?.let { appendLine("用户短评：\n${it.joinToString("\n")}") }
            cleanList(request.publicReviews).takeIf { it.isNotEmpty() }
                ?.let { appendLine("公共评论：\n${it.joinToString("\n")}") }
        }
    }.trim()

    private fun cleanList(items: List<String>): List<String> = items.map { it.trim() }.filter { it.isNotEmpty() }

    /** AI 增强结果：标注 generator=AI，并把 [RadarSummarySource.AI] 计入来源。 */
    private fun aiResult(
        request: RadarRequest,
        effectiveLevel: SpoilerLevel,
        inputSources: List<RadarSummarySource>,
        payload: SpoilerRadarOutput,
        generatedAt: Long,
    ): SpoilerRadarResult {
        val summarySources = (inputSources + RadarSummarySource.AI).distinct()
        return SpoilerRadarResult(
            workId = request.workId,
            // 以结构化来源标注覆盖展示用 sources，保证标注口径统一（短评 / Reviews / 标签 / AI）。
            output = payload.copy(sources = summarySources.map { it.label }),
            generator = AiGenerator.AI,
            requestedLevel = request.spoilerLevel,
            effectiveLevel = effectiveLevel,
            summarySources = summarySources,
            generatedAt = generatedAt,
        )
    }

    /** 规则回退结果：调用本地规则引擎，标注 generator=RULE。 */
    private fun ruleResult(
        request: RadarRequest,
        effectiveLevel: SpoilerLevel,
        inputSources: List<RadarSummarySource>,
    ): SpoilerRadarResult {
        val radarInput = RadarInput(
            tags = request.tags,
            reviewSnippets = request.userComments + request.publicReviews,
            title = request.title,
            sourceLabels = inputSources.map { it.label },
        )
        val output = LocalFallbackRadar.generate(radarInput, scrub = scrubber::scrub)
        return SpoilerRadarResult(
            workId = request.workId,
            output = output,
            generator = AiGenerator.RULE,
            requestedLevel = request.spoilerLevel,
            effectiveLevel = effectiveLevel,
            summarySources = inputSources,
            generatedAt = clock(),
        )
    }
}
