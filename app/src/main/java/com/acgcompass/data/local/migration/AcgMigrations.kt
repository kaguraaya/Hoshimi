package com.acgcompass.data.local.migration

import androidx.room.migration.Migration
/**
 * Central, explicit registry of every Room schema [Migration] for [com.acgcompass.data.local.AcgCompassDatabase]
 * ().
 *
 * ## Policy
 * ACG Compass is local-first and an app upgrade must **never** drop user data. Therefore:
 * - `fallbackToDestructiveMigration` is **intentionally never** used on the Room builder.
 * - Every schema version bump ships a hand-written [Migration] object registered in [ALL].
 * - Room's exported schema json (`$projectDir/schemas`, see `build.gradle.kts`) is committed and
 *   used to validate migrations.
 *
 * ## How to add a migration (pattern)
 * When you bump `@Database(version = N)` on `AcgCompassDatabase` from `N-1` to `N`:
 *
 * 1. Write the migration as a private `val` using DDL that preserves all existing rows:
 *
 * ```kotlin
 * // Example: v1 -> v2 adds a nullable column to the works table without data loss.
 * private val MIGRATION_1_2 = Migration(1, 2) { db ->
 *     db.execSQL("ALTER TABLE works ADD COLUMN synopsis TEXT")
 * }
 * ```
 *
 * 2. Append it to [ALL] **in ascending order**:
 *
 * ```kotlin
 * val ALL: Array<Migration> = arrayOf(
 *     MIGRATION_1_2,
 *     // MIGRATION_2_3, ...
 * )
 * ```
 *
 * 3. Commit the new `schemas/<N>.json` produced by the Room compiler and add a migration test.
 *
 * The migrations are wired into the Room builder via `.addMigrations(*AcgMigrations.ALL)` in
 * `com.acgcompass.data.local.di.DatabaseModule`.
 *
 * At schema version 1 there are no migrations yet, so [ALL] is empty — `arrayOf(*ALL)` is a safe
 * no-op on the builder.
 */
object AcgMigrations {
    private const val VERSION_11 = 11
    private const val VERSION_12 = 12
    private const val VERSION_13 = 13
    private const val VERSION_14 = 14
    private const val VERSION_15 = 15
    private const val VERSION_16 = 16
    private const val VERSION_17 = 17
    private const val VERSION_18 = 18
    private const val VERSION_19 = 19

    /**
     * v1 → v2：新增 `user_collections` 表（用户个人收藏：状态、评分、进度、短评和标签）。
     * 仅 CREATE TABLE + 索引，不触碰既有表，**不丢任何用户数据**。DDL 与 Room 为该 Entity 生成的
     * 期望 schema 完全一致（列名/类型/可空/主键/索引），以通过 Room 运行时校验。
     */
    private val MIGRATION_1_2 = Migration(1, 2) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `user_collections` (" +
                "`id` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`sourceItemId` TEXT NOT NULL, " +
                "`localWorkId` TEXT NOT NULL, " +
                "`status` TEXT, " +
                "`rating` INTEGER, " +
                "`progress` INTEGER, " +
                "`comment` TEXT, " +
                "`tags` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "`syncedAt` INTEGER NOT NULL, " +
                "`sourceUpdatedAt` TEXT, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_user_collections_localWorkId` " +
                "ON `user_collections` (`localWorkId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_user_collections_source` " +
                "ON `user_collections` (`source`)",
        )
    }

    /**
     * v2 → v3：为 `works` 表新增可空 `summary` 列，存储主来源的作品简介。
     * 仅 `ALTER TABLE ADD COLUMN`，既有行的新列默认 NULL，**不丢任何用户数据**。DDL 与 Room 为
     * 该 Entity 生成的期望 schema 一致（TEXT、可空），以通过 Room 运行时校验。
     */
    private val MIGRATION_2_3 = Migration(2, 3) { db ->
        db.execSQL("ALTER TABLE `works` ADD COLUMN `summary` TEXT")
    }

    /**
     * v3 → v4：works 表新增可空 `airDate`，存储精确到天的开播或发行日期。
     * 仅新增可空列，保留全部既有行；与 [WorkEntity] 生成的期望 schema 一致（TEXT、可空）。
     */
    private val MIGRATION_3_4 = Migration(3, 4) { db ->
        db.execSQL("ALTER TABLE `works` ADD COLUMN `airDate` TEXT")
    }

    /**
     * v4 → v5：新增 `ranking_cache` 表，存储范围键、排名次序、作品 id 和缓存时间。
     * 由 DataStore Preferences 迁移而来；仅 CREATE TABLE，不触碰既有表，**不丢任何用户数据**。
     * DDL 与 [com.acgcompass.data.local.entity.RankingCacheEntity] 生成的期望 schema 完全一致
     * （列名/类型/可空/复合主键），以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_4_5 = Migration(4, 5) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ranking_cache` (" +
                "`scopeKey` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, " +
                "`workId` TEXT NOT NULL, " +
                "`cachedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`scopeKey`, `position`))",
        )
    }

    /**
     * v5 → v6：backlog_items 新增可空 `prevStatus`，保存归档前的 Bangumi 收藏状态。
     * 吃灰归档会把状态置为搁置；此列保存原状态，以便移出吃灰馆时还原。
     * 仅新增可空列，保留全部既有行，**不丢任何用户数据**；与 [com.acgcompass.data.local.entity.BacklogItemEntity]
     * 生成的期望 schema 一致（TEXT、可空），以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_5_6 = Migration(5, 6) { db ->
        db.execSQL("ALTER TABLE `backlog_items` ADD COLUMN `prevStatus` TEXT")
    }

    /**
     * v6 → v7：新增 `work_features`（作品结构化特征缓存：社区标签计数、Staff、角色、声优和
     * 社区评分/票数 + 集数/时长/平台）与 `recommendation_exposure`（推荐曝光记录，支撑重复推荐冷却）。
     * 仅 CREATE TABLE，不触碰既有表，**不丢任何用户数据**；DDL 与 [com.acgcompass.data.local.entity.WorkFeatureEntity]
     * / [com.acgcompass.data.local.entity.RecommendationExposureEntity] 生成的期望 schema 完全一致
     * （列名/类型/可空/主键），以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_6_7 = Migration(6, 7) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `work_features` (" +
                "`subjectId` TEXT NOT NULL, " +
                "`tagCountsJson` TEXT NOT NULL, " +
                "`staff` TEXT NOT NULL, " +
                "`characters` TEXT NOT NULL, " +
                "`cv` TEXT NOT NULL, " +
                "`bangumiScore` REAL NOT NULL, " +
                "`bangumiVotes` INTEGER NOT NULL, " +
                "`eps` INTEGER NOT NULL, " +
                "`durationMin` INTEGER NOT NULL, " +
                "`platform` TEXT, " +
                "`mediaType` TEXT, " +
                "`titles` TEXT NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`subjectId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `recommendation_exposure` (" +
                "`id` TEXT NOT NULL, " +
                "`subjectId` TEXT NOT NULL, " +
                "`context` TEXT NOT NULL, " +
                "`exposedAt` INTEGER NOT NULL, " +
                "`clickedAt` INTEGER, " +
                "`dismissedAt` INTEGER, " +
                "PRIMARY KEY(`id`))",
        )
    }

    /**
     * v7 → v8：works 表新增可空 `titleCn`，存储 Bangumi 中文标题。
     * 仅 `ALTER TABLE ADD COLUMN`，既有行新列默认 NULL，**不丢任何用户数据**；与 [com.acgcompass.data.local.entity.WorkEntity]
     * 生成的期望 schema 一致（TEXT、可空），以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_7_8 = Migration(7, 8) { db ->
        db.execSQL("ALTER TABLE `works` ADD COLUMN `titleCn` TEXT")
    }

    /**
     * v8 -> v9：user_collections 表新增非空 `isPrivate`（是否「仅自己可见 / 私密」，Bangumi `private`）。
     * 仅 `ALTER TABLE ADD COLUMN` 且带 `NOT NULL DEFAULT 0`，既有行默认置公开（0），**不丢任何用户数据**；
     * 与 [com.acgcompass.data.local.entity.UserCollectionEntity] 生成的期望 schema 一致（INTEGER NOT NULL、默认 0），
     * 以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_8_9 = Migration(8, 9) { db ->
        db.execSQL("ALTER TABLE `user_collections` ADD COLUMN `isPrivate` INTEGER NOT NULL DEFAULT 0")
    }

    /**
     * v9 → v10：新增 `tag_dimensions` 缓存表，存储社区标签的 AI 升维分类结果。
     * 仅 `CREATE TABLE`，纯新增、不触碰既有表，**不丢任何用户数据**；列与顺序须与
     * [com.acgcompass.data.local.entity.TagDimensionEntity] 生成的期望 schema 完全一致
     * （tag TEXT PK、dimension/source TEXT NOT NULL、confidence REAL NOT NULL、updatedAt INTEGER NOT NULL），
     * 以通过 Room 运行时校验与迁移测试。
     */
    private val MIGRATION_9_10 = Migration(9, 10) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tag_dimensions` (" +
                "`tag` TEXT NOT NULL, " +
                "`dimension` TEXT NOT NULL, " +
                "`source` TEXT NOT NULL, " +
                "`confidence` REAL NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`tag`))",
        )
    }

    /** v10 -> v11: add explicit public cache ownership, detail/media indexes and cache version metadata. */
    private val MIGRATION_10_11 = Migration(10, 11) { db ->
        db.execSQL("ALTER TABLE `ai_results` ADD COLUMN `inputFingerprint` TEXT")
        db.execSQL("ALTER TABLE `ai_results` ADD COLUMN `promptVersion` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `tag_dimensions` ADD COLUMN `classifierVersion` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `tag_dimensions` ADD COLUMN `valid` INTEGER NOT NULL DEFAULT 1")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `public_cache_entries` (" +
                "`workId` TEXT NOT NULL, `originSource` TEXT NOT NULL, `qualityScore` REAL NOT NULL, " +
                "`estimatedBytes` INTEGER NOT NULL, `cachedAt` INTEGER NOT NULL, " +
                "`lastAccessedAt` INTEGER NOT NULL, `protected` INTEGER NOT NULL, PRIMARY KEY(`workId`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_public_cache_entries_originSource` " +
                "ON `public_cache_entries` (`originSource`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_public_cache_entries_lastAccessedAt` " +
                "ON `public_cache_entries` (`lastAccessedAt`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_public_cache_entries_protected` ON `public_cache_entries` (`protected`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `detail_cache` (" +
                "`workId` TEXT NOT NULL, `source` TEXT NOT NULL, `section` TEXT NOT NULL, " +
                "`payloadJson` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, " +
                "`expiresAt` INTEGER NOT NULL, `schemaVersion` INTEGER NOT NULL, " +
                "PRIMARY KEY(`workId`, `source`, `section`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_detail_cache_workId` ON `detail_cache` (`workId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_detail_cache_expiresAt` ON `detail_cache` (`expiresAt`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `media_cache_entries` (" +
                "`cacheKey` TEXT NOT NULL, `workId` TEXT, `source` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`url` TEXT NOT NULL, `byteSize` INTEGER NOT NULL, `adult` INTEGER NOT NULL, " +
                "`cachedAt` INTEGER NOT NULL, `lastAccessedAt` INTEGER NOT NULL, PRIMARY KEY(`cacheKey`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_cache_entries_workId` ON `media_cache_entries` (`workId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_cache_entries_source` ON `media_cache_entries` (`source`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_cache_entries_kind` ON `media_cache_entries` (`kind`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_media_cache_entries_lastAccessedAt` " +
                "ON `media_cache_entries` (`lastAccessedAt`)",
        )
        db.execSQL(
            "INSERT OR IGNORE INTO `public_cache_entries` " +
                "(`workId`, `originSource`, `qualityScore`, `estimatedBytes`, " +
                "`cachedAt`, `lastAccessedAt`, `protected`) " +
                "SELECT w.id, w.primarySource, 0, " +
                "512 + 2 * (LENGTH(w.canonicalTitle) + COALESCE(LENGTH(w.titleJa), 0) + " +
                "COALESCE(LENGTH(w.titleRomaji), 0) + COALESCE(LENGTH(w.titleEn), 0) + " +
                "COALESCE(LENGTH(w.titleCn), 0) + COALESCE(LENGTH(w.summary), 0) + " +
                "COALESCE(LENGTH(w.coverUrl), 0)), w.createdAt, w.updatedAt, " +
                "CASE WHEN EXISTS(SELECT 1 FROM backlog_items b WHERE b.workId = w.id) " +
                "OR EXISTS(SELECT 1 FROM user_collections u WHERE u.localWorkId = w.id) " +
                "OR EXISTS(SELECT 1 FROM import_items i WHERE i.workId = w.id) " +
                "OR EXISTS(SELECT 1 FROM recommendation_counts r WHERE r.workId = w.id) " +
                "THEN 1 ELSE 0 END FROM works w",
        )
    }

    /** v11 -> v12 adds resumable cache-fill state and VNDB ranking snapshots only. */
    private val MIGRATION_11_12 = Migration(VERSION_11, VERSION_12) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vndb_ranking_cache` (" +
                "`scopeKey` TEXT NOT NULL, `page` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                "`workId` TEXT NOT NULL, `rating` REAL, `voteCount` INTEGER NOT NULL, " +
                "`adult` INTEGER NOT NULL, `cachedAt` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, " +
                "`hasMore` INTEGER NOT NULL, PRIMARY KEY(`scopeKey`, `page`, `position`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS " +
                "`index_vndb_ranking_cache_workId` " +
                "ON `vndb_ranking_cache` (`workId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_vndb_ranking_cache_cachedAt` " +
                "ON `vndb_ranking_cache` (`cachedAt`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `cache_fill_state` (" +
                "`source` TEXT NOT NULL, `nextPage` INTEGER NOT NULL, `exhausted` INTEGER NOT NULL, " +
                "`lastRunAt` INTEGER, `lastResult` TEXT, `failureReason` TEXT, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`source`))",
        )
    }

    /** v12 -> v13 persists per-source failure, cooldown, and last-success state. */
    private val MIGRATION_12_13 = Migration(VERSION_12, VERSION_13) { db ->
        db.execSQL(
            "ALTER TABLE `cache_fill_state` ADD COLUMN `consecutiveFailures` " +
                "INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL("ALTER TABLE `cache_fill_state` ADD COLUMN `cooldownUntil` INTEGER")
        db.execSQL("ALTER TABLE `cache_fill_state` ADD COLUMN `lastSuccessAt` INTEGER")
        db.execSQL("ALTER TABLE `vndb_ranking_cache` ADD COLUMN `previewUrl` TEXT")
        db.execSQL("ALTER TABLE `vndb_ranking_cache` ADD COLUMN `originalUrl` TEXT")
    }

    /** v13 -> v14 adds resumable local-analysis and AI-classification task state. */
    private val MIGRATION_13_14 = Migration(VERSION_13, VERSION_14) { db ->
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `background_task_state` (" +
                "`taskKey` TEXT NOT NULL, `inputFingerprint` TEXT NOT NULL, `phase` TEXT NOT NULL, " +
                "`completed` INTEGER NOT NULL, `total` INTEGER NOT NULL, `batchIndex` INTEGER NOT NULL, " +
                "`batchCount` INTEGER NOT NULL, `automaticCount` INTEGER NOT NULL, " +
                "`reviewCount` INTEGER NOT NULL, `rejectedCount` INTEGER NOT NULL, " +
                "`failedCount` INTEGER NOT NULL, `remaining` INTEGER NOT NULL, " +
                "`startedAt` INTEGER, `completedAt` INTEGER, `updatedAt` INTEGER NOT NULL, " +
                "`error` TEXT, `retryAt` INTEGER, `lastResult` TEXT, PRIMARY KEY(`taskKey`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tag_classification_plan` (" +
                "`tag` TEXT NOT NULL, `rawTag` TEXT NOT NULL, `planId` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, `status` TEXT NOT NULL, `dimension` TEXT, " +
                "`confidence` REAL, `error` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`tag`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tag_classification_plan_planId` " +
                "ON `tag_classification_plan` (`planId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tag_classification_plan_status` " +
                "ON `tag_classification_plan` (`status`)",
        )
    }

    /** v14 -> v15 adds broadcast schedules and makes taste feature/classification state resumable. */
    private val MIGRATION_14_15 = Migration(VERSION_14, VERSION_15) { db ->
        db.execSQL("ALTER TABLE `work_features` ADD COLUMN `baseDataUpdatedAt` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `work_features` ADD COLUMN `extendedDataUpdatedAt` INTEGER")
        db.execSQL(
            "ALTER TABLE `work_features` ADD COLUMN `completeness` TEXT NOT NULL DEFAULT 'EXTENDED'",
        )
        db.execSQL(
            "UPDATE `work_features` SET `baseDataUpdatedAt` = `updatedAt`, " +
                "`extendedDataUpdatedAt` = `updatedAt`, `completeness` = 'EXTENDED'",
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `broadcast_schedule` (" +
                "`scopeKey` TEXT NOT NULL, `scheduleId` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                "`sourceItemId` TEXT NOT NULL, `workId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`titleCn` TEXT, `coverUrl` TEXT, `airingAtUtcMillis` INTEGER, " +
                "`dayOfWeek` INTEGER NOT NULL, `episode` INTEGER, " +
                "`timeToBeDetermined` INTEGER NOT NULL, `contentHash` TEXT NOT NULL, " +
                "`cachedAt` INTEGER NOT NULL, PRIMARY KEY(`scopeKey`, `scheduleId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_broadcast_schedule_workId` ON `broadcast_schedule` (`workId`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_broadcast_schedule_airingAtUtcMillis` " +
                "ON `broadcast_schedule` (`airingAtUtcMillis`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_broadcast_schedule_dayOfWeek` " +
                "ON `broadcast_schedule` (`dayOfWeek`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_broadcast_schedule_cachedAt` " +
                "ON `broadcast_schedule` (`cachedAt`)",
        )

        db.execSQL("ALTER TABLE `tag_classification_plan` RENAME TO `tag_classification_plan_v14`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tag_classification_plan` (" +
                "`tag` TEXT NOT NULL, `rawTag` TEXT NOT NULL, `planId` TEXT NOT NULL, " +
                "`position` INTEGER NOT NULL, `status` TEXT NOT NULL, `dimension` TEXT, " +
                "`confidence` REAL, `error` TEXT, `classifierVersion` INTEGER NOT NULL DEFAULT 1, " +
                "`candidateFingerprint` TEXT NOT NULL DEFAULT '', `updatedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`planId`, `tag`))",
        )
        db.execSQL(
            "INSERT INTO `tag_classification_plan` " +
                "(`tag`, `rawTag`, `planId`, `position`, `status`, `dimension`, `confidence`, `error`, " +
                "`classifierVersion`, `candidateFingerprint`, `updatedAt`) " +
                "SELECT `tag`, `rawTag`, `planId`, `position`, `status`, `dimension`, `confidence`, `error`, " +
                "1, '', `updatedAt` FROM `tag_classification_plan_v14`",
        )
        db.execSQL("DROP TABLE `tag_classification_plan_v14`")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tag_classification_plan_planId` " +
                "ON `tag_classification_plan` (`planId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_tag_classification_plan_status` " +
                "ON `tag_classification_plan` (`status`)",
        )
    }

    /** v15 -> v16 adds persisted title variants for source-aware schedule matching. */
    private val MIGRATION_15_16 = Migration(VERSION_15, VERSION_16) { db ->
        db.execSQL("ALTER TABLE `broadcast_schedule` ADD COLUMN `titleVariants` TEXT NOT NULL DEFAULT '[]'")
    }

    /** v16 -> v17 persists stable profile/AI snapshots and cache-fill pool progress. */
    private val MIGRATION_16_17 = Migration(VERSION_16, VERSION_17) { db ->
        db.execSQL(
            "ALTER TABLE `background_task_state` ADD COLUMN `notificationAcknowledged` INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            "ALTER TABLE `background_task_state` ADD COLUMN `candidateFingerprint` TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            "ALTER TABLE `cache_fill_state` ADD COLUMN `exhaustedPools` TEXT NOT NULL DEFAULT ''",
        )
    }

    /** v17 -> v18 persists the stable taste/tag accounting ledger. */
    /** v18 → v19：`work_features` 增加封面与放送日期，作品资料自成一条记录。 */
    private val MIGRATION_18_19 = Migration(VERSION_18, VERSION_19) { db ->
        db.execSQL("ALTER TABLE `work_features` ADD COLUMN `coverUrl` TEXT")
        db.execSQL("ALTER TABLE `work_features` ADD COLUMN `airDate` TEXT")
    }

    private val MIGRATION_17_18 = Migration(VERSION_17, VERSION_18) { db ->
        db.execSQL(
            "ALTER TABLE `background_task_state` ADD COLUMN `personalInputFingerprint` TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `taste_tag_processing` (" +
                "`tag` TEXT NOT NULL, `localSeen` INTEGER NOT NULL DEFAULT 1, " +
                "`aiTerminal` INTEGER NOT NULL DEFAULT 0, `lastPlanId` TEXT, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`tag`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_taste_tag_processing_aiTerminal` " +
                "ON `taste_tag_processing` (`aiTerminal`)",
        )
    }

    /**
     * All registered migrations, ordered ascending by `startVersion`.
     *
     * Spread into the Room builder with `.addMigrations(*AcgMigrations.ALL)`.
     */
    val ALL: Array<Migration> = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
    )
}
