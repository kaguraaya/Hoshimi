package com.acgcompass.data.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/** 设置项在 DataStore 里的键（名字不能改，改了会读不到旧设置）。 */
/** Preferences 键集合。仅非敏感开关，键名稳定以兼容升级。 */
internal object SettingsKeys {
    val ALLOW_AI_ANALYZE_REVIEWS = booleanPreferencesKey("allow_ai_analyze_reviews")
    val SCORE_DIFF_AUTO_BACKFILL = booleanPreferencesKey("score_diff_auto_backfill")
    val AI_DAILY_DRAW = booleanPreferencesKey("ai_daily_draw")
    val AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
    val AUTO_BACKUP_LAST_SUCCESS = longPreferencesKey("auto_backup_last_success")
    val AUTO_BACKUP_LAST_ERROR = stringPreferencesKey("auto_backup_last_error")
    val SYNC_BACKLOG_TO_BANGUMI = booleanPreferencesKey("sync_backlog_to_bangumi")
    val TASTE_MATCH_BRANCH = stringPreferencesKey("taste_match_branch")
    val RATING_BACKFILL_ATTEMPTS = stringSetPreferencesKey("rating_backfill_attempts")
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
    val SEARCH_HISTORY_ENABLED = booleanPreferencesKey("search_history_enabled")
    val LOCAL_POOL_EXPAND_OFFSET = intPreferencesKey("local_pool_expand_offset")
    val TASTE_RANK_HINT_DISMISSED = booleanPreferencesKey("taste_rank_hint_dismissed")
    val RECOMMEND_MIN_COMMUNITY_SCORE = floatPreferencesKey("recommend_min_community_score")
    val TASTE_MATCH_THRESHOLD = floatPreferencesKey("taste_match_threshold")
    val AUTO_CACHE_MAX_COUNT = intPreferencesKey("auto_cache_max_count")
    val CACHE_LIMIT_MODE = stringPreferencesKey("cache_limit_mode")
    val CACHE_SPACE_LIMIT_MB = intPreferencesKey("cache_space_limit_mb")
    val CACHE_BACKGROUND_FILL_ENABLED = booleanPreferencesKey("cache_background_fill_enabled")
    val SEARCH_HISTORY = stringPreferencesKey("search_history")
    val BANGUMI_USERNAME = stringPreferencesKey("bangumi_username")
    val LAST_SYNCED_BANGUMI_USER = stringPreferencesKey("last_synced_bangumi_user")
    val ACCOUNT_SWITCH_FROM = stringPreferencesKey("account_switch_from")
    val ACCOUNT_SWITCH_TO = stringPreferencesKey("account_switch_to")
    val RECENTLY_VIEWED = stringPreferencesKey("recently_viewed_work_ids")
    val BANGUMI_AUTH_NOTICE = stringPreferencesKey("bangumi_auth_notice")
    val OAUTH_REFRESH_ATTEMPTED_EXPIRES_AT = longPreferencesKey("oauth_refresh_attempted_expires_at")
    val OAUTH_REFRESH_RETRY_AT = longPreferencesKey("oauth_refresh_retry_at")
    val OAUTH_REFRESH_FAILURE_COUNT = intPreferencesKey("oauth_refresh_failure_count")
}
