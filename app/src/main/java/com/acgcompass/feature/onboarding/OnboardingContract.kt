package com.acgcompass.feature.onboarding

import androidx.annotation.VisibleForTesting

/**
 * 首次启动引导（Onboarding）的 UI 契约。
 *
 * 引导只承载**纯静态、可安全展示**的说明文案——绝不涉及任何凭据 / key。
 * 三条核心说明：
 * 1. 应用不提供在线播放 / 下载 / 盗版资源（合规边界）。
 * 2. 数据默认本地保存（隐私优先）。
 * 3. 可稍后在「我的 → 设置」按需配置各数据源 API key。
 */

/**
 * 单条引导说明项。
 *
 * @property title 说明标题（用户可读）。
 * @property description 说明正文。
 */
data class OnboardingHighlight(
    val title: String,
    val description: String,
)

/**
 * 引导页静态 UI 状态：标题、副标题、说明项列表与主操作按钮文案。
 *
 * 内容固定且无网络 / 凭据依赖，因此以常量形式提供，无需 [androidx.lifecycle.ViewModel] 计算。
 */
data class OnboardingUiState(
    val title: String,
    val subtitle: String,
    val highlights: List<OnboardingHighlight>,
    val confirmLabel: String,
    /** Default network choice for public Bangumi requests. */
    val communityProxyEnabledByDefault: Boolean,
    /** 个人 Token 经社区反代中转的同意勾选文案（默认不勾选也能用，仅公共搜索经反代，不发 Token）。 */
    val consentPrompt: String,
) {
    companion object {
        /** 默认引导文案，介绍反代、多来源和可选 AI 功能。 */
        val DEFAULT: OnboardingUiState = OnboardingUiState(
            title = "欢迎使用 ACG Compass",
            subtitle = "你的补番补游决策助手 · 本地优先 · 隐私安全",
            highlights = listOf(
                OnboardingHighlight(
                    title = "不提供播放与下载",
                    description = "本应用仅做信息聚合与决策辅助，不提供在线播放、下载或任何盗版资源。",
                ),
                OnboardingHighlight(
                    title = "数据默认本地保存",
                    description = "你的收藏、评分与画像默认仅保存在本机，不上传云端。",
                ),
                OnboardingHighlight(
                    title = "Bangumi 默认走社区反代",
                    description = "默认使用社区反代地址访问 Bangumi —— 官方地址在部分网络需特殊环境，反代通常可直连；" +
                        "建议保留反代。可随时在「设置 → 数据源」切回官方或更换地址。",
                ),
                OnboardingHighlight(
                    title = "多源评分开箱即用",
                    description = "已默认启用 Bangumi / Jikan(MAL 公开数据) / VNDB 等无需 API Key 的数据源，" +
                        "打开即可看多平台评分对照；AniList、MAL 官方等可按需在设置开启。",
                ),
                OnboardingHighlight(
                    title = "AI 增强为可选项",
                    description = "口味匹配、无剧透雷达等 AI 功能可选：推荐使用 DeepSeek 或其他国内 OpenAI 兼容服务，" +
                        "在「设置 → AI」填入 API Key 即可（凭据仅加密保存在本机，未配置时自动回退本地规则）。",
                ),
            ),
            confirmLabel = "开始使用",
            communityProxyEnabledByDefault = true,
            consentPrompt = "我已知晓并同意：使用个人同步（登录 Bangumi）时，我的个人 Token 将经上述社区反代地址中转。" +
                "不勾选也可使用官方地址，但个人 Token 不会发送到非官方服务。",
        )
    }
}

/**
 * 首启引导中可选快速配置的用户输入草稿；完成时一次性
 * 交由 [OnboardingViewModel] 加密保存（Bangumi 个人 Token + AI 增强）。全部可留空跳过，稍后仍可在
 * 「设置」补填。敏感值仅经 `CredentialStore` 加密落盘，绝不写入 DataStore / 日志。
 *
 * @property consentToProxyToken 是否同意个人 Token 经社区反代中转。
 * @property bangumiToken Bangumi 个人 Access Token（可选）。
 * @property aiApiKey AI 服务 API Key（可选；为空则不配置 AI）。
 * @property aiBaseUrl AI OpenAI 兼容端点（默认 DeepSeek，可编辑）。
 * @property aiModel AI 模型名（默认 deepseek-v4-flash，可编辑）。
 */
data class OnboardingSetup(
    val useCommunityProxy: Boolean = true,
    val consentToProxyToken: Boolean = false,
    val bangumiToken: String = "",
    val aiApiKey: String = "",
    val aiBaseUrl: String = OnboardingDefaults.AI_BASE_URL,
    val aiModel: String = OnboardingDefaults.AI_MODEL,
)

/** 引导页快速配置的可编辑默认值。 */
object OnboardingDefaults {
    const val AI_BASE_URL: String = "https://api.deepseek.com/v1"
    const val AI_MODEL: String = "deepseek-v4-flash"

    /** Bangumi Access Token 生成页（引导页文案引用，用户自行前往）。 */
    const val BANGUMI_TOKEN_HELP_URL: String = "https://next.bgm.tv/demo/access-token"
}

/**
 * 根据「引导是否已展示」的持久化标志，判断是否需要展示首启引导。
 *
 * 纯函数，便于单元测试：仅当 [onboardingShown] 为 `false`（即从未展示过）时返回 `true`。
 */
@VisibleForTesting
internal fun shouldShowOnboarding(onboardingShown: Boolean): Boolean = !onboardingShown
