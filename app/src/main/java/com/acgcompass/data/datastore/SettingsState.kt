package com.acgcompass.data.datastore

import com.acgcompass.domain.cache.CacheLimitMode
import com.acgcompass.domain.cache.CachePolicy
import com.acgcompass.domain.model.ThemePreset

/**
 * 主题模式（现代原生视觉：深色模式适配）。
 *
 * - [SYSTEM]：跟随系统深浅色。
 * - [LIGHT]：强制浅色。
 * - [DARK]：强制深色。
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    companion object {
        /** 从持久化字符串解析，未知值回退为 [SYSTEM]，保证升级/损坏数据不崩溃。 */
        fun fromStorage(raw: String?): ThemeMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }

    /** 持久化用的稳定字符串表示。 */
    fun toStorage(): String = name
}

/**
 * 非敏感设置和开关的聚合快照。
 *
 * 仅承载布尔开关与主题等本地偏好，**绝不包含**任何凭据 / key / token，
 * 凭据由 `CredentialStore`（EncryptedSharedPreferences + Keystore）单独管理。
 *
 * @property allowAiAnalyzeReviews 是否允许把短评发给 AI。默认关闭，AI 功能未配置时保持关闭。
 * @property recordTimeMachineSnapshots 是否记录本地时光机快照。
 * @property bangumiEnabled 是否启用默认开启的 Bangumi 主来源。
 * @property anilistEnabled 是否启用 AniList 补充来源。
 * @property jikanEnabled 是否启用无需凭据的 Jikan 来源。
 * @property malEnabled 是否启用需显式配置的 MAL 官方来源。
 * @property vndbEnabled 是否启用 VNDB 来源。
 * @property showAdultContent 是否显示 VNDB 成人内容（分级过滤）。默认关闭。
 * @property onboardingShown 首启引导是否已展示。
 * @property themeMode 主题模式（system/light/dark）。
 * @property dynamicColor 是否启用动态取色（Material You）。
 */
data class SettingsState(
    val allowAiAnalyzeReviews: Boolean = DEFAULT_ALLOW_AI_ANALYZE_REVIEWS,
    val recordTimeMachineSnapshots: Boolean = DEFAULT_RECORD_TIME_MACHINE_SNAPSHOTS,
    val bangumiEnabled: Boolean = DEFAULT_BANGUMI_ENABLED,
    val anilistEnabled: Boolean = DEFAULT_ANILIST_ENABLED,
    val jikanEnabled: Boolean = DEFAULT_JIKAN_ENABLED,
    val malEnabled: Boolean = DEFAULT_MAL_ENABLED,
    val vndbEnabled: Boolean = DEFAULT_VNDB_ENABLED,
    val showAdultContent: Boolean = DEFAULT_SHOW_ADULT_CONTENT,
    val autoTranslateVndbDescription: Boolean = DEFAULT_AUTO_TRANSLATE_VNDB_DESCRIPTION,
    val onboardingShown: Boolean = DEFAULT_ONBOARDING_SHOWN,
    val themeMode: ThemeMode = DEFAULT_THEME_MODE,
    val dynamicColor: Boolean = DEFAULT_DYNAMIC_COLOR,
    val lightThemePreset: ThemePreset = ThemePreset.OCEAN,
    val darkThemePreset: ThemePreset = ThemePreset.OCEAN,
    val predictiveBackEnabled: Boolean = DEFAULT_PREDICTIVE_BACK_ENABLED,
    /** Provider ids for which the user explicitly enabled extended thinking. Empty means off. */
    val aiThinkingEnabledProviders: Set<String> = emptySet(),
    val aiTagClassificationMax: Int = DEFAULT_AI_TAG_CLASSIFICATION_MAX,
    /** Bangumi API Base URL。默认官方；可切换社区反代或自定义地址。 */
    val bangumiApiBaseUrl: String = DEFAULT_BANGUMI_API_BASE_URL,
    /** 用户是否已确认「非官方 API + 个人 Token」的隐私风险。 */
    val bangumiNonOfficialTokenConsent: Boolean = false,
    /** 自动同步间隔（分钟）。0 = 关闭自动同步；最小有效间隔由调度层钳制到 WorkManager 下限。 */
    val autoSyncIntervalMinutes: Int = DEFAULT_AUTO_SYNC_INTERVAL_MINUTES,
    /** 推荐过滤的社区均分下限（0~10）；0 表示不限。 */
    val recommendMinCommunityScore: Float = DEFAULT_RECOMMEND_MIN_COMMUNITY_SCORE,
    /** 口味匹配度阈值（0~1）；低于阈值的作品不进入推荐，0 表示关闭。 */
    val tasteMatchThreshold: Float = DEFAULT_TASTE_MATCH_THRESHOLD,
    /**
     * 番剧信息自动缓存的目标上限（部数）。`0` = 未设置（由 App 智能决定，用 [AUTO_CACHE_DEFAULT_TARGET]）。
     * **仅**影响「公共发现池番剧信息」的自动缓存规模，不影响封面图等其他方式产生的缓存。
     */
    val autoCacheMaxCount: Int = DEFAULT_AUTO_CACHE_MAX_COUNT,
    val cacheLimitMode: CacheLimitMode = DEFAULT_CACHE_LIMIT_MODE,
    val cacheSpaceLimitMb: Int = DEFAULT_CACHE_SPACE_LIMIT_MB,
    val cacheBackgroundFillEnabled: Boolean = DEFAULT_CACHE_BACKGROUND_FILL_ENABLED,
) {
    /** 当前 Bangumi API 是否为官方地址。 */
    val isBangumiOfficialApi: Boolean
        get() = bangumiApiBaseUrl.trimEnd('/') == DEFAULT_BANGUMI_API_BASE_URL.trimEnd('/')

    companion object {
        const val DEFAULT_ALLOW_AI_ANALYZE_REVIEWS = false
        const val DEFAULT_RECORD_TIME_MACHINE_SNAPSHOTS = true
        const val DEFAULT_BANGUMI_ENABLED = true
        const val DEFAULT_ANILIST_ENABLED = true
        const val DEFAULT_JIKAN_ENABLED = true
        const val DEFAULT_MAL_ENABLED = false
        const val DEFAULT_VNDB_ENABLED = true
        const val DEFAULT_SHOW_ADULT_CONTENT = false
        const val DEFAULT_AUTO_TRANSLATE_VNDB_DESCRIPTION = false
        const val DEFAULT_ONBOARDING_SHOWN = false
        val DEFAULT_THEME_MODE = ThemeMode.SYSTEM
        const val DEFAULT_DYNAMIC_COLOR = true
        const val DEFAULT_PREDICTIVE_BACK_ENABLED = true
        const val DEFAULT_AI_TAG_CLASSIFICATION_MAX = 300
        const val MIN_AI_TAG_CLASSIFICATION_MAX = 100
        const val MAX_AI_TAG_CLASSIFICATION_MAX = 1_000

        /** Bangumi 官方 API（用于 isOfficial 判定与「切回官方」；结尾带斜杠）。 */
        const val DEFAULT_BANGUMI_API_BASE_URL = "https://api.bgm.tv/"

        /**
         * 推荐的社区反代地址（新手引导默认设为此地址）。官方 api.bgm.tv 在部分网络需特殊环境，
         * 反代可直连；但个人 Token 会经非官方主机中转，需用户在引导里显式同意（[bangumiNonOfficialTokenConsent]）。
         */
        const val COMMUNITY_PROXY_BANGUMI_API_BASE_URL = "https://bgmapi.anibt.net/"

        /** 默认关闭自动同步（0 分钟）。 */
        const val DEFAULT_AUTO_SYNC_INTERVAL_MINUTES = 0

        /** 默认推荐社区均分下限。 */
        const val DEFAULT_RECOMMEND_MIN_COMMUNITY_SCORE = 6.0f

        /**
         * 默认口味匹配度阈值为 0.4；用户选择「关闭」后保存为 0，推荐器不再施加口味下限。
         */
        const val DEFAULT_TASTE_MATCH_THRESHOLD = 0.4f

        const val DEFAULT_AUTO_CACHE_MAX_COUNT = CachePolicy.DEFAULT_COUNT_LIMIT
        val DEFAULT_CACHE_LIMIT_MODE = CacheLimitMode.COUNT
        const val DEFAULT_CACHE_SPACE_LIMIT_MB = 250
        const val DEFAULT_CACHE_BACKGROUND_FILL_ENABLED = false

        /**
         * 未设置缓存上限时使用的默认目标部数。
         * 用户在设置里显式填写上限则以其为准。
         */
        const val AUTO_CACHE_DEFAULT_TARGET = 1000
    }
}

/** Non-sensitive startup refresh gate; token contents are never persisted here. */
data class BangumiOAuthRefreshGate(
    val attemptedExpiresAt: Long? = null,
    val retryAt: Long = 0L,
    val failureCount: Int = 0,
)
