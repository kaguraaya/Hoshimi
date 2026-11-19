package com.acgcompass.data.local.di

import android.content.Context
import androidx.room.Room
import com.acgcompass.data.local.AcgCompassDatabase
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
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.VndbRankingCacheDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.data.local.migration.AcgMigrations
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing the [AcgCompassDatabase] singleton and its DAOs.
 *
 * The database is built with [Room.databaseBuilder] at application scope so the
 * whole process shares one connection pool. `fallbackToDestructiveMigration` is
 * intentionally NOT called — explicit migrations from [AcgMigrations.ALL] are
 * registered here as they are authored so an upgrade never drops user data. The upgrade-time JSON backup
 * safety net lives in `com.acgcompass.data.local.migration.DatabasePreMigrationBackup`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AcgCompassDatabase = Room.databaseBuilder(
        context,
        AcgCompassDatabase::class.java,
        AcgCompassDatabase.DATABASE_NAME,
    )
        // No fallbackToDestructiveMigration: data must survive upgrades.
        // Explicit Migration objects are registered centrally in AcgMigrations ;
        // empty at schema v1, so this spread is currently a safe no-op.
        .addMigrations(*AcgMigrations.ALL)
        .build()

    @Provides
    @Singleton
    fun provideWorkDao(db: AcgCompassDatabase): WorkDao = db.workDao()

    @Provides
    @Singleton
    fun provideSourceLinkDao(db: AcgCompassDatabase): SourceLinkDao = db.sourceLinkDao()

    @Provides
    @Singleton
    fun provideRatingDao(db: AcgCompassDatabase): RatingDao = db.ratingDao()

    @Provides
    @Singleton
    fun provideBacklogDao(db: AcgCompassDatabase): BacklogDao = db.backlogDao()

    @Provides
    @Singleton
    fun provideTagDao(db: AcgCompassDatabase): TagDao = db.tagDao()

    @Provides
    @Singleton
    fun provideImportDao(db: AcgCompassDatabase): ImportDao = db.importDao()

    @Provides
    @Singleton
    fun provideSnapshotDao(db: AcgCompassDatabase): SnapshotDao = db.snapshotDao()

    @Provides
    @Singleton
    fun provideTasteDao(db: AcgCompassDatabase): TasteDao = db.tasteDao()

    @Provides
    @Singleton
    fun provideAiResultDao(db: AcgCompassDatabase): AiResultDao = db.aiResultDao()

    @Provides
    @Singleton
    fun provideRouteNodeDao(db: AcgCompassDatabase): RouteNodeDao = db.routeNodeDao()

    @Provides
    @Singleton
    fun provideCredentialMetaDao(db: AcgCompassDatabase): CredentialMetaDao = db.credentialMetaDao()

    @Provides
    @Singleton
    fun provideUserCollectionDao(db: AcgCompassDatabase): UserCollectionDao = db.userCollectionDao()

    @Provides
    @Singleton
    fun provideRankingCacheDao(db: AcgCompassDatabase): RankingCacheDao = db.rankingCacheDao()

    @Provides
    @Singleton
    fun provideWorkFeatureDao(db: AcgCompassDatabase): WorkFeatureDao = db.workFeatureDao()

    @Provides
    @Singleton
    fun provideRecommendationExposureDao(db: AcgCompassDatabase): RecommendationExposureDao =
        db.recommendationExposureDao()

    @Provides
    @Singleton
    fun provideTagDimensionDao(db: AcgCompassDatabase): TagDimensionDao = db.tagDimensionDao()

    @Provides
    @Singleton
    fun provideCacheDao(db: AcgCompassDatabase): CacheDao = db.cacheDao()

    @Provides
    @Singleton
    fun provideVndbRankingCacheDao(db: AcgCompassDatabase): VndbRankingCacheDao = db.vndbRankingCacheDao()

    @Provides
    @Singleton
    fun provideBackgroundTaskStateDao(db: AcgCompassDatabase): BackgroundTaskStateDao = db.backgroundTaskStateDao()

    @Provides
    @Singleton
    fun provideTagClassificationPlanDao(db: AcgCompassDatabase): TagClassificationPlanDao =
        db.tagClassificationPlanDao()

    @Provides
    @Singleton
    fun provideBroadcastScheduleDao(db: AcgCompassDatabase): BroadcastScheduleDao = db.broadcastScheduleDao()

    @Provides
    @Singleton
    fun provideTasteTagProcessingDao(db: AcgCompassDatabase): TasteTagProcessingDao = db.tasteTagProcessingDao()

    @Provides
    @Singleton
    fun provideTrashDao(db: AcgCompassDatabase): com.acgcompass.data.local.dao.TrashDao = db.trashDao()
}
