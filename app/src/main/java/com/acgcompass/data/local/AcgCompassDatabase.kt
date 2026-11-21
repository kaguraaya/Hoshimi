package com.acgcompass.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.acgcompass.data.local.converter.Converters
import com.acgcompass.data.local.dao.AiResultDao
import com.acgcompass.data.local.dao.BackgroundTaskStateDao
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.BroadcastScheduleDao
import com.acgcompass.data.local.dao.CacheDao
import com.acgcompass.data.local.dao.CredentialMetaDao
import com.acgcompass.data.local.dao.ImportDao
import com.acgcompass.data.local.dao.RankingCacheDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.RecommendationExposureDao
import com.acgcompass.data.local.dao.RouteNodeDao
import com.acgcompass.data.local.dao.SnapshotDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.TagClassificationPlanDao
import com.acgcompass.data.local.dao.TagDao
import com.acgcompass.data.local.dao.TagDimensionDao
import com.acgcompass.data.local.dao.TasteDao
import com.acgcompass.data.local.dao.TasteTagProcessingDao
import com.acgcompass.data.local.dao.TrashDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.VndbRankingCacheDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.data.local.entity.AiResultEntity
import com.acgcompass.data.local.entity.BackgroundTaskStateEntity
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.BroadcastScheduleEntity
import com.acgcompass.data.local.entity.CacheFillStateEntity
import com.acgcompass.data.local.entity.ChangeLogEntity
import com.acgcompass.data.local.entity.CredentialMetaEntity
import com.acgcompass.data.local.entity.DetailCacheEntity
import com.acgcompass.data.local.entity.ImportBatchEntity
import com.acgcompass.data.local.entity.ImportItemEntity
import com.acgcompass.data.local.entity.MediaCacheEntryEntity
import com.acgcompass.data.local.entity.PublicCacheEntryEntity
import com.acgcompass.data.local.entity.RankingCacheEntity
import com.acgcompass.data.local.entity.RatingEntity
import com.acgcompass.data.local.entity.RecommendationCountEntity
import com.acgcompass.data.local.entity.RecommendationExposureEntity
import com.acgcompass.data.local.entity.RouteNodeEntity
import com.acgcompass.data.local.entity.SnapshotEntity
import com.acgcompass.data.local.entity.SourceLinkEntity
import com.acgcompass.data.local.entity.TagClassificationPlanEntity
import com.acgcompass.data.local.entity.TagDimensionEntity
import com.acgcompass.data.local.entity.TagEntity
import com.acgcompass.data.local.entity.TasteProfileEntity
import com.acgcompass.data.local.entity.TasteTagProcessingEntity
import com.acgcompass.data.local.entity.TasteTagStatEntity
import com.acgcompass.data.local.entity.TrashItemEntity
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.local.entity.VndbRankingCacheEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.entity.WorkFeatureEntity
import com.acgcompass.data.local.entity.WorkTagEntity

/**
 * The single Room database for Hoshimi and the local-first source of truth for all business data.
 *
 * Schema is exported (`exportSchema = true`, location configured in build.gradle:
 * `$projectDir/schemas`) so every version's schema json is committed and used by
 * migration tests.
 *
 * Migration policy : explicit [androidx.room.migration.Migration]
 * objects only — `fallbackToDestructiveMigration` is intentionally NOT used so an
 * app upgrade never drops user data. Migrations are registered on the
 * builder in the Hilt module, not here.
 *
 * no credential plaintext is ever stored here — only the non-sensitive
 * [CredentialMetaEntity] metadata.
 */
@Database(
    entities = [
        WorkEntity::class,
        SourceLinkEntity::class,
        RatingEntity::class,
        BacklogItemEntity::class,
        TagEntity::class,
        WorkTagEntity::class,
        RecommendationCountEntity::class,
        ImportBatchEntity::class,
        ImportItemEntity::class,
        SnapshotEntity::class,
        ChangeLogEntity::class,
        TasteProfileEntity::class,
        TasteTagStatEntity::class,
        AiResultEntity::class,
        RouteNodeEntity::class,
        CredentialMetaEntity::class,
        UserCollectionEntity::class,
        RankingCacheEntity::class,
        WorkFeatureEntity::class,
        RecommendationExposureEntity::class,
        TagDimensionEntity::class,
        PublicCacheEntryEntity::class,
        DetailCacheEntity::class,
        MediaCacheEntryEntity::class,
        VndbRankingCacheEntity::class,
        CacheFillStateEntity::class,
        BackgroundTaskStateEntity::class,
        TagClassificationPlanEntity::class,
        BroadcastScheduleEntity::class,
        TasteTagProcessingEntity::class,
        TrashItemEntity::class,
        com.acgcompass.data.local.entity.RatingBackfillStateEntity::class,
        com.acgcompass.data.local.entity.TaskRunEntity::class,
        com.acgcompass.data.local.entity.PredictionSnapshotEntity::class,
    ],
    version = 22,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AcgCompassDatabase : RoomDatabase() {

    abstract fun workDao(): WorkDao

    abstract fun sourceLinkDao(): SourceLinkDao

    abstract fun ratingDao(): RatingDao

    abstract fun backlogDao(): BacklogDao

    abstract fun tagDao(): TagDao

    abstract fun importDao(): ImportDao

    abstract fun snapshotDao(): SnapshotDao

    abstract fun tasteDao(): TasteDao

    abstract fun aiResultDao(): AiResultDao

    abstract fun routeNodeDao(): RouteNodeDao

    abstract fun credentialMetaDao(): CredentialMetaDao

    abstract fun userCollectionDao(): UserCollectionDao

    abstract fun rankingCacheDao(): RankingCacheDao

    abstract fun workFeatureDao(): WorkFeatureDao

    abstract fun recommendationExposureDao(): RecommendationExposureDao

    abstract fun tagDimensionDao(): TagDimensionDao

    abstract fun cacheDao(): CacheDao

    abstract fun vndbRankingCacheDao(): VndbRankingCacheDao

    abstract fun backgroundTaskStateDao(): BackgroundTaskStateDao

    abstract fun tagClassificationPlanDao(): TagClassificationPlanDao

    abstract fun broadcastScheduleDao(): BroadcastScheduleDao

    abstract fun trashDao(): TrashDao

    abstract fun workIdMigrationDao(): com.acgcompass.data.local.dao.WorkIdMigrationDao

    abstract fun tasteTagProcessingDao(): TasteTagProcessingDao

    abstract fun ratingBackfillDao(): com.acgcompass.data.local.dao.RatingBackfillDao

    abstract fun taskRunDao(): com.acgcompass.data.local.dao.TaskRunDao

    abstract fun predictionSnapshotDao(): com.acgcompass.data.local.dao.PredictionSnapshotDao

    companion object {
        /** Physical database file name. */
        const val DATABASE_NAME = "acg_compass.db"
    }
}
