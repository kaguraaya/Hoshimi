package com.acgcompass.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.acgcompass.domain.cache.CacheLimitMode
import com.acgcompass.domain.cache.CachePolicy
import com.acgcompass.domain.model.ThemePreset
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 单例 Preferences DataStore 委托。
 *
 * 使用 top-level [preferencesDataStore] 委托保证整个进程仅创建一个 DataStore 实例，
 * 避免多实例并发写入冲突（Jetpack DataStore 要求一个文件仅有一个实例）。
 */
private const val SETTINGS_DATASTORE_NAME = "acg_compass_settings"

/** 搜索历史最多保留条数；最新记录在前，超出后截断。 */
private const val MAX_SEARCH_HISTORY = 10

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_DATASTORE_NAME,
)

/**
 * 非敏感设置和开关的本地持久化层。
 *
 * 基于 Jetpack DataStore (Preferences) 保存布尔开关与主题等本地偏好，并以 [Flow] 对外暴露，
 * 提供 `suspend` setter 写入。**绝不**保存任何凭据 / key / token —— 凭据由
 * `CredentialStore`（EncryptedSharedPreferences + Keystore）单独加密隔离。
 *
 * 读取失败（如反序列化异常）时回退为 [SettingsState] 默认值，保证应用不崩溃。
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore: DataStore<Preferences> = context.dataStore

    /** One-shot notice emitted when an authenticated Bangumi request confirms token invalidation. */
    val bangumiAuthNotice: Flow<String?> = dataStore.data.map { it[Keys.BANGUMI_AUTH_NOTICE] }

    val bangumiOAuthRefreshGate: Flow<BangumiOAuthRefreshGate> = dataStore.data.map {
        BangumiOAuthRefreshGate(
            attemptedExpiresAt = it[Keys.OAUTH_REFRESH_ATTEMPTED_EXPIRES_AT],
            retryAt = it[Keys.OAUTH_REFRESH_RETRY_AT] ?: 0L,
            failureCount = it[Keys.OAUTH_REFRESH_FAILURE_COUNT] ?: 0,
        )
    }

    suspend fun setBangumiAuthNotice(message: String) {
        dataStore.edit { it[Keys.BANGUMI_AUTH_NOTICE] = message }
    }

    suspend fun clearBangumiAuthNotice() {
        dataStore.edit { it.remove(Keys.BANGUMI_AUTH_NOTICE) }
    }

    suspend fun recordOAuthRefreshFailure(expiresAt: Long?, retryAt: Long, failureCount: Int) {
        dataStore.edit {
            expiresAt?.let { value -> it[Keys.OAUTH_REFRESH_ATTEMPTED_EXPIRES_AT] = value }
            it[Keys.OAUTH_REFRESH_RETRY_AT] = retryAt
            it[Keys.OAUTH_REFRESH_FAILURE_COUNT] = failureCount
        }
    }

    suspend fun clearOAuthRefreshGate() {
        dataStore.edit {
            it.remove(Keys.OAUTH_REFRESH_ATTEMPTED_EXPIRES_AT)
            it.remove(Keys.OAUTH_REFRESH_RETRY_AT)
            it.remove(Keys.OAUTH_REFRESH_FAILURE_COUNT)
        }
    }

    /** 聚合所有非敏感开关的设置快照流。任一字段变更都会发射新的 [SettingsState]。 */
    val settings: Flow<SettingsState> = dataStore.data.map { prefs -> prefs.toSettingsState() }

    /** Atomically restore non-sensitive settings from a backup; credentials stay in CredentialStore. */
    suspend fun replaceSettings(state: SettingsState) {
        dataStore.edit { prefs ->
            prefs[Keys.ALLOW_AI_ANALYZE_REVIEWS] = state.allowAiAnalyzeReviews
            prefs[Keys.RECORD_TIME_MACHINE_SNAPSHOTS] = state.recordTimeMachineSnapshots
            prefs[Keys.BANGUMI_ENABLED] = state.bangumiEnabled
            prefs[Keys.ANILIST_ENABLED] = state.anilistEnabled
            prefs[Keys.JIKAN_ENABLED] = state.jikanEnabled
            prefs[Keys.MAL_ENABLED] = state.malEnabled
            prefs[Keys.VNDB_ENABLED] = state.vndbEnabled
            prefs[Keys.SHOW_ADULT_CONTENT] = state.showAdultContent
            prefs[Keys.AUTO_TRANSLATE_VNDB_DESCRIPTION] = state.autoTranslateVndbDescription
            prefs[Keys.ONBOARDING_SHOWN] = state.onboardingShown
            prefs[Keys.THEME_MODE] = state.themeMode.toStorage()
            prefs[Keys.DYNAMIC_COLOR] = state.dynamicColor
            prefs[Keys.LIGHT_THEME_PRESET] = state.lightThemePreset.name
            prefs[Keys.DARK_THEME_PRESET] = state.darkThemePreset.name
            prefs[Keys.PREDICTIVE_BACK_ENABLED] = state.predictiveBackEnabled
            prefs[Keys.AI_THINKING_ENABLED_PROVIDERS] = state.aiThinkingEnabledProviders
            prefs[Keys.AI_TAG_CLASSIFICATION_MAX] = state.aiTagClassificationMax
            prefs[Keys.BANGUMI_API_BASE_URL] = normalizeBaseUrl(state.bangumiApiBaseUrl)
            prefs[Keys.BANGUMI_NONOFFICIAL_TOKEN_CONSENT] = state.bangumiNonOfficialTokenConsent
            prefs[Keys.AUTO_SYNC_INTERVAL_MINUTES] = state.autoSyncIntervalMinutes
            prefs[Keys.RECOMMEND_MIN_COMMUNITY_SCORE] = state.recommendMinCommunityScore
            prefs[Keys.TASTE_MATCH_THRESHOLD] = state.tasteMatchThreshold
            prefs[Keys.AUTO_CACHE_MAX_COUNT] = state.autoCacheMaxCount
            prefs[Keys.CACHE_LIMIT_MODE] = state.cacheLimitMode.name
            prefs[Keys.CACHE_SPACE_LIMIT_MB] = state.cacheSpaceLimitMb
            prefs[Keys.CACHE_BACKGROUND_FILL_ENABLED] = state.cacheBackgroundFillEnabled
        }
    }

    /** Reset preferences to their defaults when the user explicitly resets local data. */
    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    /** 是否允许把短评发给 AI。 */
    val allowAiAnalyzeReviews: Flow<Boolean> =
        dataStore.data.map { it[Keys.ALLOW_AI_ANALYZE_REVIEWS] ?: SettingsState.DEFAULT_ALLOW_AI_ANALYZE_REVIEWS }

    /** 加入待补池时是否同步为 Bangumi「想看」。 */
    val syncBacklogToBangumi: Flow<Boolean> = dataStore.data.map { it[Keys.SYNC_BACKLOG_TO_BANGUMI] ?: true }

    /** 口味匹配度「大胆」风格：只看你给题材相近的作品打的分。 */
    val tasteMatchBold: Flow<Boolean> = dataStore.data.map { it[Keys.TASTE_MATCH_BOLD] ?: false }

    suspend fun setTasteMatchBold(enabled: Boolean) {
        dataStore.edit { it[Keys.TASTE_MATCH_BOLD] = enabled }
    }

    suspend fun setSyncBacklogToBangumi(enabled: Boolean) {
        dataStore.edit { it[Keys.SYNC_BACKLOG_TO_BANGUMI] = enabled }
    }

    /** 等待同步成 Bangumi「想看」的作品 id。 */
    val pendingWishWorkIds: Flow<Set<String>> = dataStore.data.map { it[Keys.PENDING_WISH_WORK_IDS].orEmpty() }

    suspend fun updatePendingWish(transform: (Set<String>) -> Set<String>) {
        dataStore.edit { prefs ->
            prefs[Keys.PENDING_WISH_WORK_IDS] = transform(prefs[Keys.PENDING_WISH_WORK_IDS].orEmpty())
        }
    }

    /** 已有的待补池是否已经补同步过一次。 */
    val wishBackfillDone: Flow<Boolean> = dataStore.data.map { it[Keys.WISH_BACKFILL_DONE] ?: false }

    suspend fun setWishBackfillDone() {
        dataStore.edit { it[Keys.WISH_BACKFILL_DONE] = true }
    }

    /** 评分差异页：进入时是否自动去其它平台补评分。 */
    val scoreDiffAutoBackfill: Flow<Boolean> = dataStore.data.map { it[Keys.SCORE_DIFF_AUTO_BACKFILL] ?: true }

    /** 是否记录本地时光机快照。 */
    val recordTimeMachineSnapshots: Flow<Boolean> = dataStore.data.map {
        it[Keys.RECORD_TIME_MACHINE_SNAPSHOTS] ?: SettingsState.DEFAULT_RECORD_TIME_MACHINE_SNAPSHOTS
    }

    /** Bangumi 数据源启用开关。 */
    val bangumiEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.BANGUMI_ENABLED] ?: SettingsState.DEFAULT_BANGUMI_ENABLED }

    /** AniList 数据源启用开关。 */
    val anilistEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.ANILIST_ENABLED] ?: SettingsState.DEFAULT_ANILIST_ENABLED }

    /** Jikan 数据源启用开关。 */
    val jikanEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.JIKAN_ENABLED] ?: SettingsState.DEFAULT_JIKAN_ENABLED }

    /** MAL 官方数据源启用开关。 */
    val malEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.MAL_ENABLED] ?: SettingsState.DEFAULT_MAL_ENABLED }

    /** VNDB 数据源启用开关。 */
    val vndbEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.VNDB_ENABLED] ?: SettingsState.DEFAULT_VNDB_ENABLED }

    /** 是否显示 VNDB 成人内容（分级过滤）。 */
    val showAdultContent: Flow<Boolean> =
        dataStore.data.map { it[Keys.SHOW_ADULT_CONTENT] ?: SettingsState.DEFAULT_SHOW_ADULT_CONTENT }

    val autoTranslateVndbDescription: Flow<Boolean> =
        dataStore.data.map {
            it[Keys.AUTO_TRANSLATE_VNDB_DESCRIPTION]
                ?: SettingsState.DEFAULT_AUTO_TRANSLATE_VNDB_DESCRIPTION
        }

    /** 首启引导是否已展示。 */
    val onboardingShown: Flow<Boolean> =
        dataStore.data.map { it[Keys.ONBOARDING_SHOWN] ?: SettingsState.DEFAULT_ONBOARDING_SHOWN }

    /** 主题模式（system/light/dark）。 */
    val themeMode: Flow<ThemeMode> =
        dataStore.data.map { ThemeMode.fromStorage(it[Keys.THEME_MODE]) }

    /** 是否启用动态取色（Material You）。 */
    val dynamicColor: Flow<Boolean> =
        dataStore.data.map { it[Keys.DYNAMIC_COLOR] ?: SettingsState.DEFAULT_DYNAMIC_COLOR }

    val lightThemePreset: Flow<ThemePreset> =
        dataStore.data.map { ThemePreset.fromStorage(it[Keys.LIGHT_THEME_PRESET]) }

    val darkThemePreset: Flow<ThemePreset> =
        dataStore.data.map { ThemePreset.fromStorage(it[Keys.DARK_THEME_PRESET]) }

    val predictiveBackEnabled: Flow<Boolean> =
        dataStore.data.map {
            it[Keys.PREDICTIVE_BACK_ENABLED] ?: SettingsState.DEFAULT_PREDICTIVE_BACK_ENABLED
        }

    /** Bangumi API Base URL。 */
    val bangumiApiBaseUrl: Flow<String> =
        dataStore.data.map { it[Keys.BANGUMI_API_BASE_URL] ?: SettingsState.DEFAULT_BANGUMI_API_BASE_URL }

    /**
     * 用户选择的 AI provider 形态标识，使用 [com.acgcompass.data.remote.ai.ProviderId] 枚举名。
     * 仅持久化「选哪个 provider 形态」这一**非敏感**选择，凭据仍由 `CredentialStore` 加密隔离。
     * 未设置时为空字符串（由选择器回退到按 baseUrl 推断或 OpenAI 形态）。
     */
    val aiProviderId: Flow<String> =
        dataStore.data.map { it[Keys.AI_PROVIDER_ID] ?: "" }

    val aiThinkingEnabledProviders: Flow<Set<String>> =
        dataStore.data.map { it[Keys.AI_THINKING_ENABLED_PROVIDERS] ?: emptySet() }

    suspend fun setAiThinkingEnabled(providerId: String, enabled: Boolean) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.AI_THINKING_ENABLED_PROVIDERS].orEmpty()
            prefs[Keys.AI_THINKING_ENABLED_PROVIDERS] =
                if (enabled) current + providerId else current - providerId
        }
    }

    val aiTagClassificationMax: Flow<Int> = dataStore.data.map {
        (it[Keys.AI_TAG_CLASSIFICATION_MAX] ?: SettingsState.DEFAULT_AI_TAG_CLASSIFICATION_MAX)
            .coerceIn(
                SettingsState.MIN_AI_TAG_CLASSIFICATION_MAX,
                SettingsState.MAX_AI_TAG_CLASSIFICATION_MAX,
            )
    }

    suspend fun setAiTagClassificationMax(value: Int) {
        dataStore.edit {
            it[Keys.AI_TAG_CLASSIFICATION_MAX] = value.coerceIn(
                SettingsState.MIN_AI_TAG_CLASSIFICATION_MAX,
                SettingsState.MAX_AI_TAG_CLASSIFICATION_MAX,
            )
        }
    }

    val tasteProfileInputFingerprint: Flow<String?> =
        dataStore.data.map { it[Keys.TASTE_PROFILE_INPUT_FINGERPRINT] }

    suspend fun setTasteProfileInputFingerprint(value: String) {
        dataStore.edit { it[Keys.TASTE_PROFILE_INPUT_FINGERPRINT] = value }
    }

    /** 持久化用户选择的 AI provider 形态标识。 */
    suspend fun setAiProviderId(providerId: String) {
        dataStore.edit { it[Keys.AI_PROVIDER_ID] = providerId }
    }

    /** 首页展示模块偏好（启用的模块 key 集合）。空集合表示「全部默认显示」。 */
    val homeModules: Flow<Set<String>> =
        dataStore.data.map { it[Keys.HOME_MODULES] ?: HomeModulePrefs.DEFAULT_KEYS }

    /** 自动同步间隔（分钟）。0 = 关闭。 */
    val autoSyncIntervalMinutes: Flow<Int> =
        dataStore.data.map { it[Keys.AUTO_SYNC_INTERVAL_MINUTES] ?: SettingsState.DEFAULT_AUTO_SYNC_INTERVAL_MINUTES }

    /** 设置自动同步间隔（分钟）。负数钳为 0（关闭）。 */
    suspend fun setAutoSyncIntervalMinutes(minutes: Int) {
        dataStore.edit { it[Keys.AUTO_SYNC_INTERVAL_MINUTES] = minutes.coerceAtLeast(0) }
    }

    /** 推荐过滤的社区均分下限（0~10，0=不限）。 */
    val recommendMinCommunityScore: Flow<Float> =
        dataStore.data.map {
            it[Keys.RECOMMEND_MIN_COMMUNITY_SCORE]
                ?: SettingsState.DEFAULT_RECOMMEND_MIN_COMMUNITY_SCORE
        }

    /** 设置推荐社区均分下限，并限制到 0~10。 */
    suspend fun setRecommendMinCommunityScore(score: Float) {
        dataStore.edit { it[Keys.RECOMMEND_MIN_COMMUNITY_SCORE] = score.coerceIn(0f, 10f) }
    }

    /** 口味匹配度阈值（0~1，0=关闭该过滤）。 */
    val tasteMatchThreshold: Flow<Float> =
        dataStore.data.map { it[Keys.TASTE_MATCH_THRESHOLD] ?: SettingsState.DEFAULT_TASTE_MATCH_THRESHOLD }

    /** 设置口味匹配度阈值，并限制到 0~1。 */
    suspend fun setTasteMatchThreshold(threshold: Float) {
        dataStore.edit { it[Keys.TASTE_MATCH_THRESHOLD] = threshold.coerceIn(0f, 1f) }
    }

    /** 待补池展示形态偏好：true 为网格，false 为列表；默认使用网格。 */
    val backlogGridMode: Flow<Boolean> =
        dataStore.data.map { it[Keys.BACKLOG_GRID_MODE] ?: true }

    /** 持久化待补池网格/列表展示形态。 */
    suspend fun setBacklogGridMode(grid: Boolean) {
        dataStore.edit { it[Keys.BACKLOG_GRID_MODE] = grid }
    }

    /**
     * 番剧信息自动缓存目标上限（部数）。0 = 未设置（由 App 智能决定，用 [SettingsState.AUTO_CACHE_DEFAULT_TARGET]）。
     * 仅影响「公共发现池番剧信息」自动缓存规模，不影响封面等其他缓存。
     */
    val autoCacheMaxCount: Flow<Int> =
        dataStore.data.map {
            (it[Keys.AUTO_CACHE_MAX_COUNT] ?: SettingsState.DEFAULT_AUTO_CACHE_MAX_COUNT)
                .takeIf { value -> value > 0 } ?: CachePolicy.DEFAULT_COUNT_LIMIT
        }

    /** 设置番剧信息自动缓存目标上限（部数）；负数钳为 0（未设置=App 决定）。 */
    suspend fun setAutoCacheMaxCount(count: Int) {
        dataStore.edit {
            it[Keys.AUTO_CACHE_MAX_COUNT] = count.coerceIn(CachePolicy.MIN_COUNT_LIMIT, CachePolicy.MAX_COUNT_LIMIT)
        }
    }

    val cacheLimitMode: Flow<CacheLimitMode> =
        dataStore.data.map { prefs ->
            CacheLimitMode.entries.firstOrNull { it.name == prefs[Keys.CACHE_LIMIT_MODE] }
                ?: SettingsState.DEFAULT_CACHE_LIMIT_MODE
        }

    val cacheSpaceLimitMb: Flow<Int> = dataStore.data.map { prefs ->
        prefs[Keys.CACHE_SPACE_LIMIT_MB] ?: SettingsState.DEFAULT_CACHE_SPACE_LIMIT_MB
    }

    val cacheBackgroundFillEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.CACHE_BACKGROUND_FILL_ENABLED] ?: SettingsState.DEFAULT_CACHE_BACKGROUND_FILL_ENABLED
    }

    suspend fun setCacheLimitMode(mode: CacheLimitMode) {
        dataStore.edit { it[Keys.CACHE_LIMIT_MODE] = mode.name }
    }

    suspend fun setCacheSpaceLimitMb(megabytes: Int) {
        dataStore.edit { it[Keys.CACHE_SPACE_LIMIT_MB] = megabytes.coerceIn(50, 5_120) }
    }

    suspend fun setCacheBackgroundFillEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CACHE_BACKGROUND_FILL_ENABLED] = enabled }
    }

    /** 最近搜索历史，以换行分隔持久化；读取时清理空项并限制条数。 */
    val searchHistory: Flow<List<String>> =
        dataStore.data.map { prefs -> prefs[Keys.SEARCH_HISTORY].toHistoryList() }

    /** 记录搜索关键词；去重后置顶并截断到上限，忽略空白值。 */
    suspend fun addSearchHistory(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        dataStore.edit { prefs ->
            val current = prefs[Keys.SEARCH_HISTORY].toHistoryList()
            val next = (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_SEARCH_HISTORY)
            prefs[Keys.SEARCH_HISTORY] = next.joinToString("\n")
        }
    }

    /** 删除单条搜索历史。 */
    suspend fun removeSearchHistory(query: String) {
        dataStore.edit { prefs ->
            val next = prefs[Keys.SEARCH_HISTORY].toHistoryList().filterNot { it == query }
            if (next.isEmpty()) {
                prefs.remove(
                    Keys.SEARCH_HISTORY,
                )
            } else {
                prefs[Keys.SEARCH_HISTORY] = next.joinToString("\n")
            }
        }
    }

    /** 清空搜索历史。 */
    suspend fun clearSearchHistory() {
        dataStore.edit { it.remove(Keys.SEARCH_HISTORY) }
    }

    /** 当前 Bangumi 用户名（登录/同步后由 `/v0/me` 写入）；未登录为 `null`。 */
    val bangumiUsername: Flow<String?> =
        dataStore.data.map { it[Keys.BANGUMI_USERNAME]?.takeIf { n -> n.isNotBlank() } }

    /** 写入 Bangumi 用户名（登录成功 / 同步时）。空白忽略。 */
    suspend fun setBangumiUsername(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        dataStore.edit { it[Keys.BANGUMI_USERNAME] = n }
    }

    /** 清除 Bangumi 用户名（清除授权时）。 */
    suspend fun clearBangumiUsername() {
        dataStore.edit { it.remove(Keys.BANGUMI_USERNAME) }
    }

    /** 最近浏览过的作品 id（最新在前，最多 12 条），供搜索页空态「最近浏览」展示。 */
    val recentlyViewedWorkIds: Flow<List<String>> =
        dataStore.data.map { prefs -> prefs[Keys.RECENTLY_VIEWED].toHistoryList() }

    /** 记录一次「点开作品」——去重置顶、截断到 12 条。 */
    suspend fun addRecentlyViewed(workId: String) {
        val id = workId.trim()
        if (id.isEmpty()) return
        dataStore.edit { prefs ->
            val next = (listOf(id) + prefs[Keys.RECENTLY_VIEWED].toHistoryList().filterNot { it == id }).take(12)
            prefs[Keys.RECENTLY_VIEWED] = next.joinToString("\n")
        }
    }

    /** 将换行分隔的持久化字符串解析为清理后的历史列表。 */
    private fun String?.toHistoryList(): List<String> =
        this?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    /** 设置启用的首页模块集合。 */
    suspend fun setHomeModules(keys: Set<String>) {
        dataStore.edit { it[Keys.HOME_MODULES] = keys }
    }

    /** 设置 Bangumi API Base URL；空白时回退官方地址，并补齐结尾斜杠。 */
    suspend fun setBangumiApiBaseUrl(url: String) {
        val normalized = normalizeBaseUrl(url)
        dataStore.edit { it[Keys.BANGUMI_API_BASE_URL] = normalized }
    }

    /** 设置是否已确认「非官方 API + 个人 Token」隐私风险。 */
    suspend fun setBangumiNonOfficialTokenConsent(consented: Boolean) {
        dataStore.edit { it[Keys.BANGUMI_NONOFFICIAL_TOKEN_CONSENT] = consented }
    }

    /** 设置是否允许把短评发给 AI。 */
    suspend fun setAllowAiAnalyzeReviews(enabled: Boolean) {
        dataStore.edit { it[Keys.ALLOW_AI_ANALYZE_REVIEWS] = enabled }
    }

    suspend fun setScoreDiffAutoBackfill(enabled: Boolean) {
        dataStore.edit { it[Keys.SCORE_DIFF_AUTO_BACKFILL] = enabled }
    }

    /** 设置是否记录本地时光机快照。 */
    suspend fun setRecordTimeMachineSnapshots(enabled: Boolean) {
        dataStore.edit { it[Keys.RECORD_TIME_MACHINE_SNAPSHOTS] = enabled }
    }

    /** 设置 Bangumi 数据源启用状态。 */
    suspend fun setBangumiEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.BANGUMI_ENABLED] = enabled }
    }

    /** 设置 AniList 数据源启用状态。 */
    suspend fun setAnilistEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.ANILIST_ENABLED] = enabled }
    }

    /** 设置 Jikan 数据源启用状态。 */
    suspend fun setJikanEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.JIKAN_ENABLED] = enabled }
    }

    /** 设置 MAL 官方数据源启用状态。 */
    suspend fun setMalEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.MAL_ENABLED] = enabled }
    }

    /** 设置 VNDB 数据源启用状态。 */
    suspend fun setVndbEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VNDB_ENABLED] = enabled }
    }

    /** 设置是否显示 VNDB 成人内容。 */
    suspend fun setShowAdultContent(enabled: Boolean) {
        dataStore.edit { it[Keys.SHOW_ADULT_CONTENT] = enabled }
    }

    suspend fun setAutoTranslateVndbDescription(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_TRANSLATE_VNDB_DESCRIPTION] = enabled }
    }

    /** 设置首启引导是否已展示。 */
    suspend fun setOnboardingShown(shown: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_SHOWN] = shown }
    }

    /** 设置主题模式。 */
    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.toStorage() }
    }

    /** 设置是否启用动态取色。 */
    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setLightThemePreset(preset: ThemePreset) {
        dataStore.edit { it[Keys.LIGHT_THEME_PRESET] = preset.name }
    }

    suspend fun setDarkThemePreset(preset: ThemePreset) {
        dataStore.edit { it[Keys.DARK_THEME_PRESET] = preset.name }
    }

    suspend fun setPredictiveBackEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.PREDICTIVE_BACK_ENABLED] = enabled }
    }

    // 榜单结果缓存位于 Room `ranking_cache` 表（见 RankingCacheDao），
    // 不再使用 Preferences；此处原 getRankingOrder/saveRankingOrder 已移除。

    /** 将原始 [Preferences] 映射为带默认值兜底的 [SettingsState]。 */
    private fun Preferences.toSettingsState(): SettingsState = SettingsState(
        allowAiAnalyzeReviews = this[Keys.ALLOW_AI_ANALYZE_REVIEWS] ?: SettingsState.DEFAULT_ALLOW_AI_ANALYZE_REVIEWS,
        scoreDiffAutoBackfill = this[Keys.SCORE_DIFF_AUTO_BACKFILL] ?: true,
        syncBacklogToBangumi = this[Keys.SYNC_BACKLOG_TO_BANGUMI] ?: true,
        tasteMatchBold = this[Keys.TASTE_MATCH_BOLD] ?: false,
        recordTimeMachineSnapshots =
        this[Keys.RECORD_TIME_MACHINE_SNAPSHOTS]
            ?: SettingsState.DEFAULT_RECORD_TIME_MACHINE_SNAPSHOTS,
        bangumiEnabled = this[Keys.BANGUMI_ENABLED] ?: SettingsState.DEFAULT_BANGUMI_ENABLED,
        anilistEnabled = this[Keys.ANILIST_ENABLED] ?: SettingsState.DEFAULT_ANILIST_ENABLED,
        jikanEnabled = this[Keys.JIKAN_ENABLED] ?: SettingsState.DEFAULT_JIKAN_ENABLED,
        malEnabled = this[Keys.MAL_ENABLED] ?: SettingsState.DEFAULT_MAL_ENABLED,
        vndbEnabled = this[Keys.VNDB_ENABLED] ?: SettingsState.DEFAULT_VNDB_ENABLED,
        showAdultContent = this[Keys.SHOW_ADULT_CONTENT] ?: SettingsState.DEFAULT_SHOW_ADULT_CONTENT,
        autoTranslateVndbDescription = this[Keys.AUTO_TRANSLATE_VNDB_DESCRIPTION]
            ?: SettingsState.DEFAULT_AUTO_TRANSLATE_VNDB_DESCRIPTION,
        onboardingShown = this[Keys.ONBOARDING_SHOWN] ?: SettingsState.DEFAULT_ONBOARDING_SHOWN,
        themeMode = ThemeMode.fromStorage(this[Keys.THEME_MODE]),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: SettingsState.DEFAULT_DYNAMIC_COLOR,
        lightThemePreset = ThemePreset.fromStorage(this[Keys.LIGHT_THEME_PRESET]),
        darkThemePreset = ThemePreset.fromStorage(this[Keys.DARK_THEME_PRESET]),
        predictiveBackEnabled = this[Keys.PREDICTIVE_BACK_ENABLED]
            ?: SettingsState.DEFAULT_PREDICTIVE_BACK_ENABLED,
        aiThinkingEnabledProviders = this[Keys.AI_THINKING_ENABLED_PROVIDERS] ?: emptySet(),
        aiTagClassificationMax = (
            this[Keys.AI_TAG_CLASSIFICATION_MAX]
                ?: SettingsState.DEFAULT_AI_TAG_CLASSIFICATION_MAX
            ).coerceIn(
            SettingsState.MIN_AI_TAG_CLASSIFICATION_MAX,
            SettingsState.MAX_AI_TAG_CLASSIFICATION_MAX,
        ),
        bangumiApiBaseUrl = this[Keys.BANGUMI_API_BASE_URL] ?: SettingsState.DEFAULT_BANGUMI_API_BASE_URL,
        bangumiNonOfficialTokenConsent = this[Keys.BANGUMI_NONOFFICIAL_TOKEN_CONSENT] ?: false,
        autoSyncIntervalMinutes =
        this[Keys.AUTO_SYNC_INTERVAL_MINUTES]
            ?: SettingsState.DEFAULT_AUTO_SYNC_INTERVAL_MINUTES,
        recommendMinCommunityScore =
        this[Keys.RECOMMEND_MIN_COMMUNITY_SCORE]
            ?: SettingsState.DEFAULT_RECOMMEND_MIN_COMMUNITY_SCORE,
        tasteMatchThreshold = this[Keys.TASTE_MATCH_THRESHOLD] ?: SettingsState.DEFAULT_TASTE_MATCH_THRESHOLD,
        autoCacheMaxCount = (this[Keys.AUTO_CACHE_MAX_COUNT] ?: SettingsState.DEFAULT_AUTO_CACHE_MAX_COUNT)
            .takeIf { it > 0 } ?: CachePolicy.DEFAULT_COUNT_LIMIT,
        cacheLimitMode = CacheLimitMode.entries.firstOrNull { it.name == this[Keys.CACHE_LIMIT_MODE] }
            ?: SettingsState.DEFAULT_CACHE_LIMIT_MODE,
        cacheSpaceLimitMb = this[Keys.CACHE_SPACE_LIMIT_MB] ?: SettingsState.DEFAULT_CACHE_SPACE_LIMIT_MB,
        cacheBackgroundFillEnabled = this[Keys.CACHE_BACKGROUND_FILL_ENABLED]
            ?: SettingsState.DEFAULT_CACHE_BACKGROUND_FILL_ENABLED,
    )

    /** 归一化 Base URL：去除空白、空值回退官方地址并补齐结尾斜杠；可达性由连接测试负责。 */
    private fun normalizeBaseUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return SettingsState.DEFAULT_BANGUMI_API_BASE_URL
        return if (t.endsWith("/")) t else "$t/"
    }

    /** Preferences 键集合。仅非敏感开关，键名稳定以兼容升级。 */
    private object Keys {
        val ALLOW_AI_ANALYZE_REVIEWS = booleanPreferencesKey("allow_ai_analyze_reviews")
        val SCORE_DIFF_AUTO_BACKFILL = booleanPreferencesKey("score_diff_auto_backfill")
        val SYNC_BACKLOG_TO_BANGUMI = booleanPreferencesKey("sync_backlog_to_bangumi")
        val TASTE_MATCH_BOLD = booleanPreferencesKey("taste_match_bold")
        val PENDING_WISH_WORK_IDS = stringSetPreferencesKey("pending_wish_work_ids")
        val WISH_BACKFILL_DONE = booleanPreferencesKey("wish_backfill_done")
        val RECORD_TIME_MACHINE_SNAPSHOTS = booleanPreferencesKey("record_time_machine_snapshots")
        val BANGUMI_ENABLED = booleanPreferencesKey("bangumi_enabled")
        val ANILIST_ENABLED = booleanPreferencesKey("anilist_enabled")
        val JIKAN_ENABLED = booleanPreferencesKey("jikan_enabled")
        val MAL_ENABLED = booleanPreferencesKey("mal_enabled")
        val VNDB_ENABLED = booleanPreferencesKey("vndb_enabled")
        val SHOW_ADULT_CONTENT = booleanPreferencesKey("show_adult_content")
        val AUTO_TRANSLATE_VNDB_DESCRIPTION = booleanPreferencesKey("auto_translate_vndb_description")
        val ONBOARDING_SHOWN = booleanPreferencesKey("onboarding_shown")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LIGHT_THEME_PRESET = stringPreferencesKey("light_theme_preset")
        val DARK_THEME_PRESET = stringPreferencesKey("dark_theme_preset")
        val PREDICTIVE_BACK_ENABLED = booleanPreferencesKey("predictive_back_enabled")
        val BANGUMI_API_BASE_URL = stringPreferencesKey("bangumi_api_base_url")
        val AI_PROVIDER_ID = stringPreferencesKey("ai_provider_id")
        val AI_THINKING_ENABLED_PROVIDERS = stringSetPreferencesKey("ai_thinking_enabled_providers")
        val AI_TAG_CLASSIFICATION_MAX = intPreferencesKey("ai_tag_classification_max")
        val TASTE_PROFILE_INPUT_FINGERPRINT = stringPreferencesKey("taste_profile_input_fingerprint")
        val BANGUMI_NONOFFICIAL_TOKEN_CONSENT = booleanPreferencesKey("bangumi_nonofficial_token_consent")
        val HOME_MODULES = stringSetPreferencesKey("home_modules")
        val AUTO_SYNC_INTERVAL_MINUTES = intPreferencesKey("auto_sync_interval_minutes")
        val BACKLOG_GRID_MODE = booleanPreferencesKey("backlog_grid_mode")
        val RECOMMEND_MIN_COMMUNITY_SCORE = floatPreferencesKey("recommend_min_community_score")
        val TASTE_MATCH_THRESHOLD = floatPreferencesKey("taste_match_threshold")
        val AUTO_CACHE_MAX_COUNT = intPreferencesKey("auto_cache_max_count")
        val CACHE_LIMIT_MODE = stringPreferencesKey("cache_limit_mode")
        val CACHE_SPACE_LIMIT_MB = intPreferencesKey("cache_space_limit_mb")
        val CACHE_BACKGROUND_FILL_ENABLED = booleanPreferencesKey("cache_background_fill_enabled")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
        val BANGUMI_USERNAME = stringPreferencesKey("bangumi_username")
        val RECENTLY_VIEWED = stringPreferencesKey("recently_viewed_work_ids")
        val BANGUMI_AUTH_NOTICE = stringPreferencesKey("bangumi_auth_notice")
        val OAUTH_REFRESH_ATTEMPTED_EXPIRES_AT = longPreferencesKey("oauth_refresh_attempted_expires_at")
        val OAUTH_REFRESH_RETRY_AT = longPreferencesKey("oauth_refresh_retry_at")
        val OAUTH_REFRESH_FAILURE_COUNT = intPreferencesKey("oauth_refresh_failure_count")
    }
}

/** 首页可配置模块定义（key + 展示名 + 默认是否开启）。 */
enum class HomeModulePref(val key: String, val label: String, val defaultOn: Boolean) {
    TODAY_DECISION("today_decision", "今日决策（今晚看什么）", true),
    SEASONAL_NEW("seasonal_new", "本季新番", true),
    SEASONAL_HOT("seasonal_hot", "时间表", true),
    BACKLOG_OVERVIEW("backlog_overview", "待补池概览", true),
    CONTINUE("continue", "继续看 / 玩 / 读", true),
    SYNC_REMINDER("sync_reminder", "同步提醒", true),
    DAILY_DRAW("daily_draw", "今日补番签", true),
}

/** 首页模块偏好工具。 */
object HomeModulePrefs {
    val DEFAULT_KEYS: Set<String> = HomeModulePref.entries.filter { it.defaultOn }.map { it.key }.toSet()
}
