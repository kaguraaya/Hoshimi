package com.acgcompass.data.backup

import com.acgcompass.data.credential.RedactedCredentials
import kotlinx.serialization.Serializable

/**
 * 备份和导出的可序列化 JSON 信封。
 *
 * **默认零凭据**：缺省构造下 [includesCredentials] 为
 * `false` 且 [credentials] 为 `null`，序列化结果**绝不**包含任何 key/token/secret 明文。仅当用户
 * 显式选择导出凭据并二次确认时，[credentials] 才被填充为**脱敏**的 [RedactedCredentials]（如
 * `sk-****…ab`），[includesCredentials] 同时置 `true`。
 *
 * **round-trip 可还原**：信封仅由可序列化的纯数据类构成，
 * `deserialize(serialize(x)) == x`（见 [BackupSerializer]）。每张业务表映射为一个顶层数组，
 * 主键随行携带，便于导入时按业务主键合并不覆盖（[BackupMerger]）。
 *
 * 字段覆盖 works / backlog / ratings / reviews / tags / importBatches / snapshots /
 * tasteProfile / settings 等业务数据。为保证升级不丢数据，与作品强耦合的卫星表
 * （source_links / work_tags / recommendation_counts）以及明细表（import_items / change_logs /
 * taste_tag_stats）一并以独立数组导出。
 */
@Serializable
data class BackupEnvelope(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAt: Long = 0L,
    val appVersion: String = "",
    /** 默认 `false`：备份不含凭据。 */
    val includesCredentials: Boolean = false,
    val works: List<BackupWork> = emptyList(),
    val sourceLinks: List<BackupSourceLink> = emptyList(),
    val recommendationCounts: List<BackupRecommendationCount> = emptyList(),
    val backlog: List<BackupBacklogItem> = emptyList(),
    val ratings: List<BackupRating> = emptyList(),
    /** 短评数组占位：当前短评随个人评分（[ratings]）建模，保留字段以兼容设计 schema 与未来扩展。 */
    val reviews: List<BackupReview> = emptyList(),
    val tags: List<BackupTag> = emptyList(),
    val workTags: List<BackupWorkTag> = emptyList(),
    val importBatches: List<BackupImportBatch> = emptyList(),
    val importItems: List<BackupImportItem> = emptyList(),
    val snapshots: List<BackupSnapshot> = emptyList(),
    val changeLogs: List<BackupChangeLog> = emptyList(),
    val tasteProfiles: List<BackupTasteProfile> = emptyList(),
    val tasteTagStats: List<BackupTasteTagStat> = emptyList(),
    val userCollections: List<BackupUserCollection> = emptyList(),
    val workFeatures: List<BackupWorkFeature> = emptyList(),
    val tagDimensions: List<BackupTagDimension> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
    /** 默认 `null`：绝不导出凭据明文。仅显式脱敏导出时为非 null。 */
    val credentials: RedactedCredentials? = null,
) {
    companion object {
        /** 当前备份 schema 版本。 */
        const val CURRENT_SCHEMA_VERSION: Int = 2
    }
}

/** 规范化作品（works 表）。字段镜像 `WorkEntity`，枚举类字段以 String 原样保留。 */
@Serializable
data class BackupWork(
    val id: String,
    val canonicalTitle: String,
    val titleJa: String? = null,
    val titleRomaji: String? = null,
    val titleEn: String? = null,
    val aliases: List<String> = emptyList(),
    val mediaType: String,
    val year: Int? = null,
    val status: String,
    val episodes: Int? = null,
    val episodeMinutes: Int? = null,
    val volumes: Int? = null,
    val estPlayMinutes: Int? = null,
    val coverUrl: String? = null,
    val primarySource: String,
    val completionCostBucket: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 多源标识链接（source_links 表）。 */
@Serializable
data class BackupSourceLink(
    val id: String,
    val workId: String,
    val sourceId: String,
    val sourceItemId: String,
    val matchConfidence: Float,
    val userOverridden: Boolean,
    val linkedAt: Long,
)

/** 被安利次数计数（recommendation_counts 表）。 */
@Serializable
data class BackupRecommendationCount(
    val workId: String,
    val recommendedCount: Int,
    val lastRecommendedAt: Long,
)

/** 待补池记录（backlog_items 表）。 */
@Serializable
data class BackupBacklogItem(
    val workId: String,
    val priority: String,
    val moodTags: List<String> = emptyList(),
    val riskTags: List<String> = emptyList(),
    val note: String? = null,
    val addedAt: Long,
    val dustDays: Int,
    val inDustMuseum: Boolean,
)

/** 每源评分（ratings 表）。个人评分/短评亦以此建模。 */
@Serializable
data class BackupRating(
    val id: String,
    val workId: String,
    val sourceId: String,
    val score: Float,
    val voteCount: Int,
    val rank: Int? = null,
    val fetchedAt: Long,
    val missing: Boolean,
)

/**
 * 短评模型。当前短评随个人记录保存；该数组保留用于兼容已有备份格式。
 */
@Serializable
data class BackupReview(
    val id: String,
    val workId: String,
    val content: String,
    val createdAt: Long,
)

/** 标签分类法（tags 表）。 */
@Serializable
data class BackupTag(
    val id: String,
    val category: String,
    val name: String,
)

/** 作品-标签关联（work_tags 表）。 */
@Serializable
data class BackupWorkTag(
    val workId: String,
    val tagId: String,
)

/** 导入批次（import_batches 表）。 */
@Serializable
data class BackupImportBatch(
    val id: String,
    val name: String,
    val createdAt: Long,
    val source: String? = null,
    val recognizedCount: Int,
    val successCount: Int,
    val failureCount: Int,
)

/** 导入明细行（import_items 表）。 */
@Serializable
data class BackupImportItem(
    val id: String,
    val batchId: String,
    val rawText: String,
    val parsedTitle: String? = null,
    val workId: String? = null,
    val matchConfidence: Float,
    val status: String,
)

/** 时光机快照（snapshots 表）。 */
@Serializable
data class BackupSnapshot(
    val id: String,
    val takenAt: Long,
    val kind: String,
    val payloadHash: String,
)

/** 快照变更记录（change_logs 表）。 */
@Serializable
data class BackupChangeLog(
    val id: String,
    val snapshotId: String,
    val workId: String,
    val changeType: String,
    val field: String? = null,
    val oldValue: String? = null,
    val newValue: String? = null,
    val changedAt: Long,
)

/** 口味画像（taste_profiles 表）。 */
@Serializable
data class BackupTasteProfile(
    val id: String,
    val strictness: Float,
    val avgScore: Float,
    val highScoreRarity: Float,
    val commonScoreBand: String? = null,
    val titles: List<String> = emptyList(),
    val confidence: Float,
    val generatedAt: Long,
)

/** 口味画像每标签统计（taste_tag_stats 表）。 */
@Serializable
data class BackupTasteTagStat(
    val id: String,
    val profileId: String,
    val tagName: String,
    val bucket: String,
    val count: Int,
)

@Serializable
data class BackupUserCollection(
    val id: String,
    val source: String,
    val sourceItemId: String,
    val localWorkId: String,
    val status: String? = null,
    val rating: Int? = null,
    val progress: Int? = null,
    val comment: String? = null,
    val tags: List<String> = emptyList(),
    val updatedAt: Long,
    val syncedAt: Long,
    val sourceUpdatedAt: String? = null,
    val isPrivate: Boolean = false,
)

@Serializable
data class BackupWorkFeature(
    val subjectId: String,
    val tagCountsJson: String,
    val staff: List<String> = emptyList(),
    val characters: List<String> = emptyList(),
    val cv: List<String> = emptyList(),
    val bangumiScore: Float,
    val bangumiVotes: Int,
    val eps: Int,
    val durationMin: Int,
    val platform: String? = null,
    val mediaType: String? = null,
    val titles: List<String> = emptyList(),
    val baseDataUpdatedAt: Long = 0L,
    val extendedDataUpdatedAt: Long? = null,
    val completeness: String = "EXTENDED",
    val updatedAt: Long,
)

@Serializable
data class BackupTagDimension(
    val tag: String,
    val dimension: String,
    val source: String,
    val confidence: Float,
    val updatedAt: Long,
    val classifierVersion: Int = 1,
    val valid: Boolean = true,
)

/**
 * 非敏感设置快照（settings 表）。字段镜像 `SettingsState`；主题以 String 持久化。
 * **绝不**包含任何凭据。
 */
@Serializable
data class BackupSettings(
    val allowAiAnalyzeReviews: Boolean = false,
    val recordTimeMachineSnapshots: Boolean = true,
    val bangumiEnabled: Boolean = true,
    val anilistEnabled: Boolean = false,
    val jikanEnabled: Boolean = false,
    val malEnabled: Boolean = false,
    val vndbEnabled: Boolean = false,
    val showAdultContent: Boolean = false,
    val autoTranslateVndbDescription: Boolean = false,
    val onboardingShown: Boolean = false,
    val themeMode: String = "SYSTEM",
    val dynamicColor: Boolean = true,
    val lightThemePreset: String = "OCEAN",
    val darkThemePreset: String = "OCEAN",
    val predictiveBackEnabled: Boolean = true,
    val aiThinkingEnabledProviders: Set<String> = emptySet(),
    val aiTagClassificationMax: Int = 300,
    val bangumiApiBaseUrl: String = "https://api.bgm.tv/",
    val bangumiNonOfficialTokenConsent: Boolean = false,
    val autoSyncIntervalMinutes: Int = 0,
    val recommendMinCommunityScore: Float = 6f,
    val tasteMatchThreshold: Float = 0.4f,
    val autoCacheMaxCount: Int = 1000,
    val cacheLimitMode: String = "COUNT",
    val cacheSpaceLimitMb: Int = 250,
    val cacheBackgroundFillEnabled: Boolean = false,
)
