package com.acgcompass.data.remote.ai

import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 运行时解析得到的 AI 端点配置。
 *
 * **安全约束**：该对象在内存中承载 [apiKey] 明文，仅用于即时构造出站请求的
 * 鉴权头，**绝不**记录日志、写入 Room/DataStore/备份。展示一律使用脱敏视图。
 *
 * @property apiKey       用户提供的 API Key（缺失为 `null` → provider 抛出 `Unauthorized`）。
 * @property baseUrl      用户提供的 Base URL；为 `null` 时 provider 使用各自内置默认值
 *                        （[CUSTOM_OPENAI_COMPAT][ProviderId.CUSTOM_OPENAI_COMPAT] 无默认值，必须由用户提供）。
 * @property defaultModel 用户配置的默认模型名；当 [AiRequest.model] 为空白时回落到它。
 */
data class AiEndpointConfig(
    val apiKey: String? = null,
    val baseUrl: String? = null,
    val defaultModel: String? = null,
    val thinkingEnabled: Boolean = false,
)

/**
 * AI 端点配置来源。
 *
 * 抽象出「在调用时取得 baseUrl/model/key」这一步，便于：
 * - 生产环境从加密的 `CredentialStore` 读取（[CredentialStoreAiCredentialSource]）；
 * - 测试中以假实现注入固定配置，无需触碰 Android Keystore。
 */
interface AiCredentialSource {

    /** 读取当前 AI 端点配置（凭据缺失时各字段为 `null`）。 */
    suspend fun load(): AiEndpointConfig
}

/**
 * 默认实现：从 [CredentialStore] 读取 [SourceId.AI_PROVIDER] 的明文凭据并投影为
 * [AiEndpointConfig]。
 *
 * 仅在内存中持有明文，读取失败时返回全空配置（由 provider 转化为 `Unauthorized`，不崩溃）。
 */
@Singleton
class CredentialStoreAiCredentialSource @Inject constructor(
    private val credentialStore: CredentialStore,
    private val settingsDataStore: SettingsDataStore,
) : AiCredentialSource {

    override suspend fun load(): AiEndpointConfig {
        val secret = runCatching { credentialStore.get(SourceId.AI_PROVIDER) }.getOrNull()
        val providerId = runCatching { settingsDataStore.aiProviderId.first() }.getOrDefault("")
        val thinkingEnabled = runCatching {
            providerId in settingsDataStore.aiThinkingEnabledProviders.first()
        }.getOrDefault(false)
        return AiEndpointConfig(
            apiKey = secret?.apiKey?.takeIf { it.isNotBlank() },
            baseUrl = secret?.baseUrl?.takeIf { it.isNotBlank() },
            defaultModel = resolveAiDefaultModel(providerId, secret?.model),
            thinkingEnabled = thinkingEnabled,
        )
    }
}

internal const val DEEPSEEK_DEFAULT_MODEL = "deepseek-v4-flash"

internal fun resolveAiDefaultModel(providerId: String, savedModel: String?): String? =
    savedModel?.takeIf { it.isNotBlank() }
        ?: DEEPSEEK_DEFAULT_MODEL.takeIf { providerId == ProviderId.DEEPSEEK.name }
