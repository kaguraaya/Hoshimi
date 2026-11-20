package com.acgcompass.feature.detail

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.AiRunOptions
import com.acgcompass.domain.ai.AiRunResult
import com.acgcompass.domain.ai.AiTask
import com.acgcompass.domain.ai.VndbTranslationCachePolicy
import com.acgcompass.domain.ai.VndbTranslationOutput
import com.acgcompass.domain.model.AiTaskType
import com.acgcompass.domain.repository.AiRepository
import com.acgcompass.domain.repository.DetailInteractionRepository
import com.acgcompass.domain.visualnovel.VisualNovelDetail
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 视觉小说详情的简介翻译：开了自动翻译、Bangumi 没有中文简介、原文不含中文时，先用缓存，没有缓存且配置了 AI 再翻译并缓存。
 * 从详情页 ViewModel 拆出来，逻辑不变。
 */
internal class VndbDescriptionTranslator(
    private val detailInteractions: DetailInteractionRepository,
    private val aiRepository: AiRepository,
    private val aiEngine: AiEngine,
    private val json: Json,
) {
    /** 返回要显示的译文；不需要或拿不到时返回 null。 */
    suspend fun translate(detail: VisualNovelDetail): String? {
        val enabled = detailInteractions.shouldAutoTranslateVndbDescription()
        val source = detail.description
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.takeIf { enabled && detail.bangumiSummary.isNullOrBlank() }
            ?.takeUnless(::containsCjk)
            ?: return null
        return cached(detail.id, source) ?: generate(detail, source, VndbTranslationCachePolicy.fingerprint(source))
    }

    private suspend fun cached(workId: String, source: String): String? {
        val cached = (aiRepository.getCached(workId, AiTaskType.VNDB_TRANSLATION) as? AppResult.Success)?.data
        val reusable = cached != null &&
            VndbTranslationCachePolicy.isReusable(cached.inputFingerprint, cached.promptVersion, source)
        return cached
            ?.takeIf { reusable }
            ?.let {
                runCatchingCancellable {
                    json.decodeFromString<VndbTranslationOutput>(
                        it.payloadJson,
                    )
                }.getOrNull()
            }
            ?.text
            ?.takeIf(String::isNotBlank)
    }

    private suspend fun generate(detail: VisualNovelDetail, source: String, fingerprint: String): String? {
        if (!detailInteractions.isAiConfigured()) return null
        val result = aiEngine.run(AiTask.VndbTranslation(detail.id, source), AiRunOptions(confirmed = true))
        val success = result as? AiRunResult.Success
        val translated = success?.payload?.text?.trim()?.takeIf(String::isNotBlank)
        if (success != null && translated != null) {
            aiRepository.put(
                success.result.copy(
                    inputFingerprint = fingerprint,
                    promptVersion = VndbTranslationCachePolicy.PROMPT_VERSION,
                ),
            )
        }
        return translated
    }

    private fun containsCjk(value: String): Boolean = value.any { it in '一'..'鿿' }
}
