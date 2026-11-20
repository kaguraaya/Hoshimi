package com.acgcompass.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SecretBundle
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.datastore.SettingsState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首启引导 ViewModel。MVVM + Hilt + StateFlow。
 *
 * 职责：
 * - 读取 [SettingsDataStore.onboardingShown]，对外暴露 [shouldShowOnboarding] 供导航层决定
 *   是否在进入主界面前先展示引导。
 * - 用户完成引导后调用 [onOnboardingComplete]，将 `onboardingShown` 持久化为 `true`，
 *   保证引导只展示一次。
 *
 * 引导文案为纯静态内容（[OnboardingUiState.DEFAULT]），不在此计算。
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
    private val credentialStore: CredentialStore,
) : ViewModel() {

    /**
     * 是否需要展示首启引导。
     *
     * 初始值为 `false`：在首帧 DataStore 尚未发射前不展示引导，避免老用户「闪现」引导页；
     * 首次启动用户会在 DataStore 首次发射（`onboardingShown == false`）后切换为 `true`。
     */
    val shouldShowOnboarding: StateFlow<Boolean> =
        settingsDataStore.onboardingShown
            .map { shown -> shouldShowOnboarding(shown) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = false,
            )

    /**
     * 用户完成引导时：
     * - 可选快速配置：一次性加密保存 Bangumi 个人 Token 与 AI 配置；
     *   到 `CredentialStore`（与设置页同一落盘路径）；均可留空跳过，空值不写入。敏感值绝不写 DataStore / 日志。
     * - 把 Bangumi API 设为社区反代（[SettingsState.COMMUNITY_PROXY_BANGUMI_API_BASE_URL]）——官方地址在部分网络
     *   需特殊环境，反代通常可直连；**仅首启写入**（引导只展示一次），不影响已配置官方地址的老用户。
     * - [OnboardingSetup.consentToProxyToken] 决定是否允许个人 Token 发往非官方地址；未同意时仅公共搜索经过反代。
     * - 持久化 `onboardingShown = true`，引导不再展示。
     */
    fun onOnboardingComplete(setup: OnboardingSetup, onPersisted: () -> Unit = {}) {
        viewModelScope.launch {
            // 先持久化完成标志，全部写入结束后再回调导航，避免 ViewModel 销毁导致写入被取消。
            settingsDataStore.setOnboardingShown(true)
            // 可选 Bangumi Token 与既有凭据合并，仅覆盖 token。
            val bangumiToken = setup.bangumiToken.trim().takeIf { it.isNotEmpty() }
            if (bangumiToken != null) {
                val existing = runCatchingCancellable { credentialStore.get(SourceId.BANGUMI) }.getOrNull()
                credentialStore.put(SourceId.BANGUMI, (existing ?: SecretBundle()).copy(token = bangumiToken))
            }
            // 仅在 API Key 非空时保存 AI 配置，并根据 baseUrl 持久化 provider 类型。
            val aiApiKey = setup.aiApiKey.trim().takeIf { it.isNotEmpty() }
            if (aiApiKey != null) {
                val baseUrl = setup.aiBaseUrl.trim().takeIf { it.isNotEmpty() }
                val model = setup.aiModel.trim().takeIf { it.isNotEmpty() }
                val existing = runCatchingCancellable { credentialStore.get(SourceId.AI_PROVIDER) }.getOrNull()
                credentialStore.put(
                    SourceId.AI_PROVIDER,
                    (existing ?: SecretBundle()).copy(apiKey = aiApiKey, baseUrl = baseUrl, model = model),
                )
                settingsDataStore.setAiProviderId(inferProviderId(baseUrl))
            }
            settingsDataStore.setBangumiApiBaseUrl(
                if (setup.useCommunityProxy) {
                    SettingsState.COMMUNITY_PROXY_BANGUMI_API_BASE_URL
                } else {
                    SettingsState.DEFAULT_BANGUMI_API_BASE_URL
                },
            )
            // A token may only be sent to a non-official endpoint when both
            // switches are enabled.  Turning the public proxy off revokes the
            // consent for this setup, even if the checkbox was left selected.
            settingsDataStore.setBangumiNonOfficialTokenConsent(
                setup.useCommunityProxy && setup.consentToProxyToken,
            )
            // 全部持久化完成后再离开引导页。
            onPersisted()
        }
    }

    /**
     * 由 OpenAI 兼容端点 [baseUrl] 推断 provider 类型。
     * 引导页默认推荐 DeepSeek；未识别的自定义端点归为 CUSTOM。
     */
    private fun inferProviderId(baseUrl: String?): String {
        val u = baseUrl?.lowercase().orEmpty()
        return when {
            u.isBlank() || u.contains("deepseek") -> PROVIDER_DEEPSEEK
            u.contains("openrouter") -> PROVIDER_OPENROUTER
            u.contains("googleapis") || u.contains("generativelanguage") -> PROVIDER_GEMINI
            u.contains("openai.com") -> PROVIDER_OPENAI
            else -> PROVIDER_CUSTOM_OPENAI_COMPAT
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val PROVIDER_DEEPSEEK = "DEEPSEEK"
        const val PROVIDER_OPENROUTER = "OPENROUTER"
        const val PROVIDER_GEMINI = "GEMINI"
        const val PROVIDER_OPENAI = "OPENAI"
        const val PROVIDER_CUSTOM_OPENAI_COMPAT = "CUSTOM_OPENAI_COMPAT"
    }
}
