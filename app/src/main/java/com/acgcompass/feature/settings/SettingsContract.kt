package com.acgcompass.feature.settings

import com.acgcompass.data.credential.SecretBundle
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.ThemeMode
import com.acgcompass.domain.model.ThemePreset

/**
 * 设置页（Settings_Screen）的 UI 状态契约。
 *
 * 这里只承载**可安全展示**的状态：凭据草稿（用户当前在输入框里键入但尚未保存的值）仅存在于
 * 内存中的 [CredentialFieldState.value]，已保存的凭据**绝不**回读到 UI——已配置状态仅通过
 * [SourceCardState.configured] 等非敏感元数据体现。
 */

/**
 * 单个凭据输入字段的键。与 [SecretBundle] 的字段一一对应，便于装配/读取。
 */
enum class CredentialFieldKey {
    TOKEN,
    CLIENT_ID,
    CLIENT_SECRET,

    /** AI Provider API key（敏感）。 */
    API_KEY,

    /** AI Provider Base URL（非敏感配置，自定义 OpenAI 兼容端点）。 */
    BASE_URL,

    /** AI Provider 模型名（非敏感配置）。 */
    MODEL,
}

/**
 * 单个凭据输入框的状态。
 *
 * @property key 字段标识。
 * @property label 字段显示名（用户可读）。
 * @property value 用户当前输入的草稿值（仅内存，未保存）。
 * @property sensitive 是否为敏感字段：敏感字段默认掩码显示（PasswordVisualTransformation）。
 * @property revealed 临时显示开关是否开启；开启后明文显示，超时后自动复位。
 * @property optional 是否为可选字段（如 MAL 的 Client Secret）。
 * @property placeholder 输入提示。
 * @property savedMasked 该敏感字段已保存且当前无新输入草稿，应以掩码占位形式展示，
 *   且**禁止**临时显示（明文绝不回读 UI）；用户重新输入新值时该标志自动失效。
 */
data class CredentialFieldState(
    val key: CredentialFieldKey,
    val label: String,
    val value: String = "",
    val sensitive: Boolean = true,
    val revealed: Boolean = false,
    val optional: Boolean = false,
    val placeholder: String = "",
    val savedMasked: Boolean = false,
)

/** 连接测试的三态。 */
enum class ConnectionTestStatus { IDLE, TESTING, SUCCESS, FAILED }

/**
 * 连接测试结果状态：成功 / 失败原因 / 文档入口。
 *
 * @property status 当前测试阶段。
 * @property message 成功或失败的可读说明。
 * @property docUrl 失败时提供的官方文档入口（可空）。
 */
data class ConnectionTestState(
    val status: ConnectionTestStatus = ConnectionTestStatus.IDLE,
    val message: String? = null,
    val docUrl: String? = null,
)

/**
 * 源卡片内的开关项（如数据源启用、同步、列表读取、成人内容显示）。
 *
 * @property key 开关标识（用于回调路由）。
 * @property label 开关显示名。
 * @property description 辅助说明（可空）。
 * @property checked 当前是否开启（来自 DataStore）。
 * @property enabled 是否可交互（如 MAL 仅在显式配置后才允许启用）。
 */
data class SourceToggleState(
    val key: String,
    val label: String,
    val description: String? = null,
    val checked: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * 单个数据源的可折叠设置卡片状态。
 *
 * @property sourceId 数据源标识。
 * @property title 卡片标题。
 * @property docUrl 官方文档链接（公开文档，绝不含 key/token）。
 * @property expanded 卡片是否展开。
 * @property configured 是否已配置凭据（来自 CredentialStore 元数据，非明文）。
 * @property statusText 状态文案（已配置 / 未配置 / 测试成功 / 测试失败）。
 * @property lastTestedAtText 最后测试时间文案（可空）。
 * @property fields 凭据输入字段列表。
 * @property toggles 源相关开关列表。
 * @property infoNotes 提示信息（如 Jikan 频率/降级、MAL OAuth/PKCE 状态、VNDB 分级过滤说明）。
 * @property username 当前用户名（仅 Bangumi；暂无能力时为 null → 显示「暂无数据」）。
 * @property test 连接测试状态。
 * @property hasDraft 是否存在未保存的输入草稿（用于控制保存按钮可用性）。
 */
data class SourceCardState(
    val sourceId: SourceId,
    val title: String,
    val docUrl: String,
    val expanded: Boolean = false,
    val configured: Boolean = false,
    val statusText: String = "",
    val lastTestedAtText: String? = null,
    val fields: List<CredentialFieldState> = emptyList(),
    val toggles: List<SourceToggleState> = emptyList(),
    val infoNotes: List<String> = emptyList(),
    val showUsername: Boolean = false,
    val username: String? = null,
    val test: ConnectionTestState = ConnectionTestState(),
    val hasDraft: Boolean = false,
)

/**
 * 可选的 AI Provider 预设。
 *
 * 仅承载**公开**的默认 Base URL 与官方文档链接，**绝不**包含任何 key。
 * 选择某 Provider 时可作为 Base URL / 模型名的默认填充建议；[CUSTOM] 表示用户自定义 OpenAI 兼容端点。
 *
 * @property displayName 下拉显示名。
 * @property defaultBaseUrl 默认 Base URL 建议（公开端点，可空表示需用户自填）。
 * @property defaultModel 默认模型名建议（可空）。
 * @property docUrl 官方文档链接。
 * @property editableBaseUrl Base URL 是否可由用户编辑（自定义端点为 true）。
 */
enum class AiProviderOption(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val docUrl: String,
    val editableBaseUrl: Boolean = true,
) {
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-5.2",
        docUrl = "https://platform.openai.com/docs/api-reference",
    ),
    GEMINI(
        displayName = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
        defaultModel = "gemini-1.5-flash",
        docUrl = "https://ai.google.dev/gemini-api/docs",
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-v4-flash",
        docUrl = "https://api-docs.deepseek.com/",
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        defaultModel = "openai/gpt-5.2",
        docUrl = "https://openrouter.ai/docs",
    ),
    CUSTOM(
        displayName = "自定义（OpenAI 兼容）",
        defaultBaseUrl = "",
        defaultModel = "",
        docUrl = "https://platform.openai.com/docs/api-reference",
    ),
}

/**
 * AI Provider 设置卡片状态。
 *
 * @property expanded 卡片是否展开。
 * @property configured 是否已配置 API key（来自 CredentialStore 元数据，非明文）。
 * @property statusText 状态文案。
 * @property lastTestedAtText 最后测试时间文案（可空）。
 * @property options 可选的 Provider 预设列表。
 * @property selectedProvider 当前选择的 Provider。
 * @property fields 凭据/配置输入字段（API key / Base URL / 模型名）。
 * @property docUrl 当前 Provider 的官方文档链接。
 * @property budgetReminder 预算/用量提醒文案。
 * @property test 连接测试状态。
 * @property hasDraft 是否存在未保存草稿（控制保存按钮可用性）。
 */
data class AiProviderCardState(
    val expanded: Boolean = false,
    val configured: Boolean = false,
    val statusText: String = "",
    val lastTestedAtText: String? = null,
    val options: List<AiProviderOption> = AiProviderOption.entries,
    val selectedProvider: AiProviderOption = AiProviderOption.DEEPSEEK,
    val fields: List<CredentialFieldState> = emptyList(),
    val docUrl: String = "",
    val budgetReminder: String = "",
    val test: ConnectionTestState = ConnectionTestState(),
    val hasDraft: Boolean = false,
    val thinkingEnabled: Boolean = false,
    val maxClassificationTags: Int = 300,
)

/**
 * 隐私控制卡片状态。
 *
 * 数据清除 / 缓存清除 / 导入 / 导出为导航级入口（由宿主页面提供回调）；本状态仅承载可折叠与两个开关。
 *
 * @property expanded 卡片是否展开。
 * @property allowAiAnalyzeReviews 是否允许把短评发给 AI（默认关闭）。
 * @property recordTimeMachineSnapshots 是否记录本地时光机快照。
 */
data class PrivacyCardState(
    val expanded: Boolean = false,
    val allowAiAnalyzeReviews: Boolean = false,
    val recordTimeMachineSnapshots: Boolean = true,
    /** 自动同步间隔（分钟），0 表示关闭。 */
    val autoSyncIntervalMinutes: Int = 0,
    /** 推荐过滤的社区均分下限（0~10，0=不限）。 */
    val recommendMinCommunityScore: Float = 6.0f,
    /** 口味匹配度阈值（0~1，0=关闭过滤）。 */
    val tasteMatchThreshold: Float = 0.0f,
    /** 评分差异页打开时自动去其它平台补评分。 */
    val scoreDiffAutoBackfill: Boolean = true,
    /** 加入待补池时同步为 Bangumi「想看」。 */
    val syncBacklogToBangumi: Boolean = true,
    /** 口味匹配度分支（[com.acgcompass.domain.taste.TasteMatchBranch.key]）。 */
    val tasteMatchBranch: String = "standard",
)

/**
 * 设置页聚合 UI 状态：各数据源卡片集合 + AI Provider 卡片 + 隐私卡片 + 保存提示可见性。
 *
 * @property cards 各源卡片状态（按固定顺序：Bangumi → AniList → Jikan → MAL → VNDB）。
 * @property aiProvider AI Provider 卡片状态。
 * @property privacy 隐私控制卡片状态。
 * @property saveNoticeVisible 保存后是否展示「凭据仅保存在本机」提示。
 */
data class SettingsUiState(
    val cards: List<SourceCardState> = emptyList(),
    val aiProvider: AiProviderCardState = AiProviderCardState(),
    val privacy: PrivacyCardState = PrivacyCardState(),
    val saveNoticeVisible: Boolean = false,
    val bangumiApi: BangumiApiUiState = BangumiApiUiState(),
    /** 自动同步间隔（分钟），0 表示关闭。 */
    val autoSyncIntervalMinutes: Int = 0,
    /** 应用主题模式。 */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Android 12+ Material You 动态配色。 */
    val dynamicColor: Boolean = true,
    val lightThemePreset: ThemePreset = ThemePreset.OCEAN,
    val darkThemePreset: ThemePreset = ThemePreset.OCEAN,
    val predictiveBackEnabled: Boolean = true,
)

/** Bangumi API 地址模式。 */
enum class BangumiApiMode { OFFICIAL, COMMUNITY, CUSTOM }

/**
 * Bangumi API 地址配置 UI 状态。
 *
 * @property mode 当前模式（官方 / 社区反代 / 自定义）。
 * @property baseUrl 当前生效的 Base URL。
 * @property customDraft 自定义输入框草稿。
 * @property consent 是否已确认「非官方 API + 个人 Token」隐私风险。
 * @property isOfficial 当前是否官方地址。
 * @property candidates 已联网核验可用的社区反代候选（label to url）。
 */
data class BangumiApiUiState(
    val mode: BangumiApiMode = BangumiApiMode.OFFICIAL,
    val baseUrl: String = "https://api.bgm.tv/",
    val customDraft: String = "",
    val consent: Boolean = false,
    val isOfficial: Boolean = true,
    val candidates: List<Pair<String, String>> = BANGUMI_API_CANDIDATES,
)

/**
 * 经核验可代理 Bangumi `/v0/` 的社区反代候选。
 * 仅作为用户**主动选择**项，非默认；不保证长期稳定性。
 */
val BANGUMI_API_CANDIDATES: List<Pair<String, String>> = listOf(
    "bgmapi.anibt.net" to "https://bgmapi.anibt.net/",
    "api.bangumi.one" to "https://api.bangumi.one/",
)
