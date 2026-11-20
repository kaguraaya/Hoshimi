package com.acgcompass.data.remote.ai

import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.datastore.SettingsDataStore
import kotlinx.coroutines.flow.first

/**
 * 解析「用户当前选择的 AI provider」。
 *
 * `AiEngine` 据此从 [AiProviderRegistry] 取出对应 [AiProvider]。把「选择哪个 provider」抽象为接口，
 * 便于：
 * - 生产环境从设置持久化读取用户选择（[PersistedAiProviderSelector]）；
 * - 测试中注入固定选择，无需触碰存储。
 *
 * 注意：provider 的**凭据**（baseUrl/model/key）始终由 [AiCredentialSource] 在调用时读取，
 * 本接口只负责「选哪个 provider 形态」，不持有任何明文。
 */
interface AiProviderSelector {

    /** 返回当前选择的 provider 标识。 */
    suspend fun selected(): ProviderId
}

/**
 * 默认实现：返回 [default]（缺省 [ProviderId.OPENAI]）。仅用于测试 / 兜底。
 */
class DefaultAiProviderSelector(
    private val default: ProviderId = ProviderId.OPENAI,
) : AiProviderSelector {
    override suspend fun selected(): ProviderId = default
}

/**
 * 读取用户在设置页持久化的 provider 形态选择（[SettingsDataStore.aiProviderId]）。
 *
 * 解析优先级：
 * 1. 已持久化且可解析的 [ProviderId] → 直接使用（用户的显式选择，含 Gemini）。
 * 2. 未持久化（老用户 / 未显式选过）→ 依据已配置的 baseUrl 主机名**推断** provider 形态，
 *    使 DeepSeek / Gemini / OpenRouter 等历史配置也能命中正确的调用协议。
 * 3. 仍无法判断 → 回退 [ProviderId.OPENAI]（OpenAI 兼容形态，最通用）。
 *
 * 凭据仍由 [AiCredentialSource] 运行时读取，本类只读「形态」选择，不持有任何明文。
 */
class PersistedAiProviderSelector(
    private val settingsDataStore: SettingsDataStore,
    private val credentialSource: AiCredentialSource,
) : AiProviderSelector {

    override suspend fun selected(): ProviderId {
        val persisted = ProviderId.fromStorage(
            runCatchingCancellable { settingsDataStore.aiProviderId.first() }.getOrNull()?.takeIf { it.isNotBlank() },
        )
        if (persisted != null) return persisted

        // 未保存 provider 类型时，按已配置 baseUrl 主机名推断。
        val baseUrl = runCatchingCancellable { credentialSource.load().baseUrl }.getOrNull()
        return inferFromBaseUrl(baseUrl)
    }

    private fun inferFromBaseUrl(baseUrl: String?): ProviderId {
        val host = baseUrl?.lowercase().orEmpty()
        return when {
            host.isBlank() -> ProviderId.OPENAI
            "generativelanguage.googleapis" in host || "gemini" in host -> ProviderId.GEMINI
            "deepseek" in host -> ProviderId.DEEPSEEK
            "openrouter" in host -> ProviderId.OPENROUTER
            "api.openai.com" in host -> ProviderId.OPENAI
            // 其它自建 / 未知 OpenAI 兼容端点。
            else -> ProviderId.CUSTOM_OPENAI_COMPAT
        }
    }
}
