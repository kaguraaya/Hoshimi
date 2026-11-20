package com.acgcompass.data.datastore

import androidx.datastore.preferences.core.Preferences
import com.acgcompass.domain.cache.CacheLimitMode
import com.acgcompass.domain.cache.CachePolicy
import com.acgcompass.domain.model.ThemePreset

/** 将原始 [Preferences] 映射为带默认值兜底的 [SettingsState]。 */
internal fun Preferences.toSettingsState(): SettingsState = SettingsState(
    allowAiAnalyzeReviews =
    this[SettingsKeys.ALLOW_AI_ANALYZE_REVIEWS] ?: SettingsState.DEFAULT_ALLOW_AI_ANALYZE_REVIEWS,
    scoreDiffAutoBackfill = this[SettingsKeys.SCORE_DIFF_AUTO_BACKFILL] ?: true,
    syncBacklogToBangumi = this[SettingsKeys.SYNC_BACKLOG_TO_BANGUMI] ?: true,
    aiDailyDraw = this[SettingsKeys.AI_DAILY_DRAW] ?: false,
    searchHistoryEnabled = this[SettingsKeys.SEARCH_HISTORY_ENABLED] ?: true,
    tasteMatchBranch = this[SettingsKeys.TASTE_MATCH_BRANCH] ?: DEFAULT_MATCH_BRANCH,
    recordTimeMachineSnapshots =
    this[SettingsKeys.RECORD_TIME_MACHINE_SNAPSHOTS]
        ?: SettingsState.DEFAULT_RECORD_TIME_MACHINE_SNAPSHOTS,
    bangumiEnabled = this[SettingsKeys.BANGUMI_ENABLED] ?: SettingsState.DEFAULT_BANGUMI_ENABLED,
    anilistEnabled = this[SettingsKeys.ANILIST_ENABLED] ?: SettingsState.DEFAULT_ANILIST_ENABLED,
    jikanEnabled = this[SettingsKeys.JIKAN_ENABLED] ?: SettingsState.DEFAULT_JIKAN_ENABLED,
    malEnabled = this[SettingsKeys.MAL_ENABLED] ?: SettingsState.DEFAULT_MAL_ENABLED,
    vndbEnabled = this[SettingsKeys.VNDB_ENABLED] ?: SettingsState.DEFAULT_VNDB_ENABLED,
    showAdultContent = this[SettingsKeys.SHOW_ADULT_CONTENT] ?: SettingsState.DEFAULT_SHOW_ADULT_CONTENT,
    autoTranslateVndbDescription = this[SettingsKeys.AUTO_TRANSLATE_VNDB_DESCRIPTION]
        ?: SettingsState.DEFAULT_AUTO_TRANSLATE_VNDB_DESCRIPTION,
    onboardingShown = this[SettingsKeys.ONBOARDING_SHOWN] ?: SettingsState.DEFAULT_ONBOARDING_SHOWN,
    themeMode = ThemeMode.fromStorage(this[SettingsKeys.THEME_MODE]),
    dynamicColor = this[SettingsKeys.DYNAMIC_COLOR] ?: SettingsState.DEFAULT_DYNAMIC_COLOR,
    lightThemePreset = ThemePreset.fromStorage(this[SettingsKeys.LIGHT_THEME_PRESET]),
    darkThemePreset = ThemePreset.fromStorage(this[SettingsKeys.DARK_THEME_PRESET]),
    predictiveBackEnabled = this[SettingsKeys.PREDICTIVE_BACK_ENABLED]
        ?: SettingsState.DEFAULT_PREDICTIVE_BACK_ENABLED,
    aiThinkingEnabledProviders = this[SettingsKeys.AI_THINKING_ENABLED_PROVIDERS] ?: emptySet(),
    aiTagClassificationMax = (
        this[SettingsKeys.AI_TAG_CLASSIFICATION_MAX]
            ?: SettingsState.DEFAULT_AI_TAG_CLASSIFICATION_MAX
        ).coerceIn(
        SettingsState.MIN_AI_TAG_CLASSIFICATION_MAX,
        SettingsState.MAX_AI_TAG_CLASSIFICATION_MAX,
    ),
    bangumiApiBaseUrl = this[SettingsKeys.BANGUMI_API_BASE_URL] ?: SettingsState.DEFAULT_BANGUMI_API_BASE_URL,
    bangumiNonOfficialTokenConsent = this[SettingsKeys.BANGUMI_NONOFFICIAL_TOKEN_CONSENT] ?: false,
    autoSyncIntervalMinutes =
    this[SettingsKeys.AUTO_SYNC_INTERVAL_MINUTES]
        ?: SettingsState.DEFAULT_AUTO_SYNC_INTERVAL_MINUTES,
    recommendMinCommunityScore =
    this[SettingsKeys.RECOMMEND_MIN_COMMUNITY_SCORE]
        ?: SettingsState.DEFAULT_RECOMMEND_MIN_COMMUNITY_SCORE,
    tasteMatchThreshold = this[SettingsKeys.TASTE_MATCH_THRESHOLD] ?: SettingsState.DEFAULT_TASTE_MATCH_THRESHOLD,
    autoCacheMaxCount = (this[SettingsKeys.AUTO_CACHE_MAX_COUNT] ?: SettingsState.DEFAULT_AUTO_CACHE_MAX_COUNT)
        .takeIf { it > 0 } ?: CachePolicy.DEFAULT_COUNT_LIMIT,
    cacheLimitMode = CacheLimitMode.entries.firstOrNull { it.name == this[SettingsKeys.CACHE_LIMIT_MODE] }
        ?: SettingsState.DEFAULT_CACHE_LIMIT_MODE,
    cacheSpaceLimitMb = this[SettingsKeys.CACHE_SPACE_LIMIT_MB] ?: SettingsState.DEFAULT_CACHE_SPACE_LIMIT_MB,
    cacheBackgroundFillEnabled = this[SettingsKeys.CACHE_BACKGROUND_FILL_ENABLED]
        ?: SettingsState.DEFAULT_CACHE_BACKGROUND_FILL_ENABLED,
)
