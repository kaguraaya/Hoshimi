package com.acgcompass.data.remote.ai.di

import com.acgcompass.core.network.di.AiOkHttpClient
import com.acgcompass.data.remote.ai.AiCredentialSource
import com.acgcompass.data.remote.ai.AiDefaults
import com.acgcompass.data.remote.ai.AiEngineImpl
import com.acgcompass.data.remote.ai.AiHttpCaller
import com.acgcompass.data.remote.ai.AiProvider
import com.acgcompass.data.remote.ai.AiProviderSelector
import com.acgcompass.data.remote.ai.CredentialStoreAiCredentialSource
import com.acgcompass.data.remote.ai.GeminiProvider
import com.acgcompass.data.remote.ai.OpenAiCompatibleProvider
import com.acgcompass.data.remote.ai.OpenAiProviderCapabilities
import com.acgcompass.data.remote.ai.ProviderId
import com.acgcompass.data.remote.ai.ProviderIdKey
import com.acgcompass.data.remote.ai.prompt.SpoilerGuard
import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.SpoilerScrubber
import com.acgcompass.domain.usecase.GenerateSpoilerRadarUseCase
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * AI 子系统 Hilt 模块（design「AiProvider 抽象」）。
 *
 * 职责：
 * - 绑定 [AiCredentialSource] 到 [CredentialStoreAiCredentialSource]（运行时从加密
 *   `CredentialStore` 读取 baseUrl/model/key，代码零密钥）。
 * - 复用 `core/network` 的 AI 专用 [OkHttpClient]（[AiOkHttpClient] 限定符，宽松超时档）
 *   构建共享的 [AiHttpCaller]。
 * - 以 `@IntoMap @ProviderIdKey(...)` 将五个 provider 聚合为 `Map<ProviderId, AiProvider>`，
 *   供 [com.acgcompass.data.remote.ai.AiProviderRegistry] 注入并按需解析。
 *
 * OpenAI / DeepSeek / OpenRouter / 自定义兼容共享 [OpenAiCompatibleProvider]，仅默认 baseUrl
 * 与是否支持 `json_schema` 不同；Gemini 单独由 [GeminiProvider] 映射。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    /** 生产环境凭据来源：从加密 `CredentialStore` 读取。 */
    @Binds
    @Singleton
    abstract fun bindAiCredentialSource(impl: CredentialStoreAiCredentialSource): AiCredentialSource

    /** AI 调用管线：绑定领域 [AiEngine] 契约到实现。 */
    @Binds
    @Singleton
    abstract fun bindAiEngine(impl: AiEngineImpl): AiEngine

    companion object {

        /**
         * 剧透文本净化器：把领域抽象 [SpoilerScrubber] 绑定到
         * [SpoilerGuard.scrubText]，供本地规则版雷达对产出文本再做一次抽象化过滤。
         */
        @Provides
        @Singleton
        fun provideSpoilerScrubber(): SpoilerScrubber = SpoilerScrubber { text -> SpoilerGuard.scrubText(text) }

        /**
         * AI provider 选择器：读取用户在设置页持久化的 provider 形态选择
         * （[com.acgcompass.data.datastore.SettingsDataStore]），未设置时按已配置 baseUrl 推断，
         * 使 Gemini / DeepSeek / OpenRouter 等选择真正生效。凭据仍由 [AiCredentialSource]
         * 运行时读取，本绑定不持有任何明文。
         */
        @Provides
        @Singleton
        fun provideAiProviderSelector(
            settingsDataStore: com.acgcompass.data.datastore.SettingsDataStore,
            credentialSource: AiCredentialSource,
        ): AiProviderSelector = com.acgcompass.data.remote.ai.PersistedAiProviderSelector(
            settingsDataStore = settingsDataStore,
            credentialSource = credentialSource,
        )

        /**
         * 防剧透雷达生成用例：编排 AI 增强与规则回退、剧透等级与来源标注。
         */
        @Provides
        @Singleton
        fun provideGenerateSpoilerRadarUseCase(
            aiEngine: AiEngine,
            scrubber: SpoilerScrubber,
        ): GenerateSpoilerRadarUseCase = GenerateSpoilerRadarUseCase(aiEngine, scrubber)

        /** AI provider 共享的挂起式 HTTP 执行器（复用 AI 专用 OkHttp 实例）。 */
        @Provides
        @Singleton
        fun provideAiHttpCaller(@AiOkHttpClient client: OkHttpClient): AiHttpCaller = AiHttpCaller(client)

        @Provides
        @Singleton
        @IntoMap
        @ProviderIdKey(ProviderId.OPENAI)
        fun provideOpenAiProvider(
            credentialSource: AiCredentialSource,
            httpCaller: AiHttpCaller,
            json: Json,
        ): AiProvider = OpenAiCompatibleProvider(
            id = ProviderId.OPENAI,
            capabilities = OpenAiProviderCapabilities(
                defaultBaseUrl = AiDefaults.OPENAI_BASE_URL,
                structuredOutput = true,
            ),
            credentialSource = credentialSource,
            httpCaller = httpCaller,
            json = json,
        )

        @Provides
        @Singleton
        @IntoMap
        @ProviderIdKey(ProviderId.DEEPSEEK)
        fun provideDeepSeekProvider(
            credentialSource: AiCredentialSource,
            httpCaller: AiHttpCaller,
            json: Json,
        ): AiProvider = OpenAiCompatibleProvider(
            id = ProviderId.DEEPSEEK,
            capabilities = OpenAiProviderCapabilities(
                defaultBaseUrl = AiDefaults.DEEPSEEK_BASE_URL,
                structuredOutput = false,
                supportsThinking = true,
            ),
            credentialSource = credentialSource,
            httpCaller = httpCaller,
            json = json,
        )

        @Provides
        @Singleton
        @IntoMap
        @ProviderIdKey(ProviderId.OPENROUTER)
        fun provideOpenRouterProvider(
            credentialSource: AiCredentialSource,
            httpCaller: AiHttpCaller,
            json: Json,
        ): AiProvider = OpenAiCompatibleProvider(
            id = ProviderId.OPENROUTER,
            capabilities = OpenAiProviderCapabilities(
                defaultBaseUrl = AiDefaults.OPENROUTER_BASE_URL,
                structuredOutput = false,
            ),
            credentialSource = credentialSource,
            httpCaller = httpCaller,
            json = json,
        )

        @Provides
        @Singleton
        @IntoMap
        @ProviderIdKey(ProviderId.CUSTOM_OPENAI_COMPAT)
        fun provideCustomOpenAiCompatProvider(
            credentialSource: AiCredentialSource,
            httpCaller: AiHttpCaller,
            json: Json,
        ): AiProvider = OpenAiCompatibleProvider(
            id = ProviderId.CUSTOM_OPENAI_COMPAT,
            capabilities = OpenAiProviderCapabilities(
                defaultBaseUrl = null,
                structuredOutput = false,
            ),
            credentialSource = credentialSource,
            httpCaller = httpCaller,
            json = json,
        )

        @Provides
        @Singleton
        @IntoMap
        @ProviderIdKey(ProviderId.GEMINI)
        fun provideGeminiProvider(
            credentialSource: AiCredentialSource,
            httpCaller: AiHttpCaller,
            json: Json,
        ): AiProvider = GeminiProvider(
            credentialSource = credentialSource,
            httpCaller = httpCaller,
            json = json,
        )
    }
}
