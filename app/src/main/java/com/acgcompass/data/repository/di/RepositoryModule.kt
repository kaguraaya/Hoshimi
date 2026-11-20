package com.acgcompass.data.repository.di

import com.acgcompass.core.common.DefaultDispatcherProvider
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.network.DataSourceOrchestrator
import com.acgcompass.core.network.DefaultDataSourceOrchestrator
import com.acgcompass.data.repository.AiRepositoryImpl
import com.acgcompass.data.repository.BacklogRepositoryImpl
import com.acgcompass.data.repository.BackupRepositoryImpl
import com.acgcompass.data.repository.BangumiOAuthRepositoryImpl
import com.acgcompass.data.repository.BroadcastScheduleRepositoryImpl
import com.acgcompass.data.repository.CacheRepositoryImpl
import com.acgcompass.data.repository.DetailInteractionRepositoryImpl
import com.acgcompass.data.repository.DetailRepositoryImpl
import com.acgcompass.data.repository.ImportRepositoryImpl
import com.acgcompass.data.repository.LocalDataResetterImpl
import com.acgcompass.data.repository.RouteMapRepositoryImpl
import com.acgcompass.data.repository.TasteProfileRepositoryImpl
import com.acgcompass.data.repository.TimeMachineRepositoryImpl
import com.acgcompass.data.repository.VisualNovelRepositoryImpl
import com.acgcompass.data.repository.WorkFeatureRepositoryImpl
import com.acgcompass.data.repository.WorkRepositoryImpl
import com.acgcompass.data.taste.TagClassificationCoordinator
import com.acgcompass.data.taste.TasteAnalysisCoordinator
import com.acgcompass.domain.auth.BangumiOAuthRepository
import com.acgcompass.domain.repository.AiRepository
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.BackupRepository
import com.acgcompass.domain.repository.CacheFillController
import com.acgcompass.domain.repository.CacheRepository
import com.acgcompass.domain.repository.DetailInteractionRepository
import com.acgcompass.domain.repository.DetailRepository
import com.acgcompass.domain.repository.ImportRepository
import com.acgcompass.domain.repository.LocalDataResetter
import com.acgcompass.domain.repository.PublicCacheWorkRepository
import com.acgcompass.domain.repository.RouteMapRepository
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.repository.TimeMachineRepository
import com.acgcompass.domain.repository.VisualNovelRepository
import com.acgcompass.domain.repository.WorkRepository
import com.acgcompass.domain.schedule.BroadcastScheduleRepository
import com.acgcompass.domain.taste.TagClassificationController
import com.acgcompass.domain.taste.TasteAnalysisController
import com.acgcompass.domain.taste.WorkFeatureRepository
import com.acgcompass.domain.usecase.AggregateRatingsUseCase
import com.acgcompass.domain.usecase.ScoringHabitCalculator
import com.acgcompass.domain.usecase.TasteStatsCalculator
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 仓库层 Hilt 绑定。
 *
 * - [bindWorkRepository]：把领域契约 [WorkRepository] 绑定到 data 层实现
 *   [WorkRepositoryImpl]，使表现层只依赖领域接口（依赖方向 UI → Domain ← Data）。
 * - 伴生对象提供 [WorkRepositoryImpl] 所需、尚无其它模块提供的协作者：
 *   [DispatcherProvider]、[DataSourceOrchestrator]、[AggregateRatingsUseCase]
 *   （均为纯实现，单例即可，无 Android 依赖）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAiRepository(impl: AiRepositoryImpl): AiRepository

    @Binds
    @Singleton
    abstract fun bindBangumiOAuthRepository(impl: BangumiOAuthRepositoryImpl): BangumiOAuthRepository

    @Binds
    @Singleton
    abstract fun bindWorkRepository(impl: WorkRepositoryImpl): WorkRepository

    @Binds
    @Singleton
    abstract fun bindLocalDataResetter(impl: LocalDataResetterImpl): LocalDataResetter

    @Binds
    @Singleton
    abstract fun bindPublicCacheWorkRepository(impl: WorkRepositoryImpl): PublicCacheWorkRepository

    @Binds
    @Singleton
    abstract fun bindCacheFillController(impl: WorkRepositoryImpl): CacheFillController

    @Binds
    @Singleton
    abstract fun bindVisualNovelRepository(impl: VisualNovelRepositoryImpl): VisualNovelRepository

    @Binds
    @Singleton
    abstract fun bindBacklogRepository(impl: BacklogRepositoryImpl): BacklogRepository

    @Binds
    @Singleton
    abstract fun bindTasteCodeRepository(
        impl: com.acgcompass.data.tastecode.TasteCodeRepositoryImpl,
    ): com.acgcompass.domain.tastecode.TasteCodeRepository

    @Binds
    @Singleton
    abstract fun bindBangumiSubjectSink(
        impl: com.acgcompass.data.taste.LocalPoolSubjectSink,
    ): com.acgcompass.data.remote.bangumi.BangumiSubjectSink

    @Binds
    abstract fun bindWatchReportRepository(
        impl: com.acgcompass.data.report.WatchReportRepositoryImpl,
    ): com.acgcompass.domain.report.WatchReportRepository

    @Binds
    @Singleton
    abstract fun bindTasteRankingRepository(
        impl: com.acgcompass.data.taste.TasteRankingRepositoryImpl,
    ): com.acgcompass.domain.taste.TasteRankingRepository

    @Binds
    @Singleton
    abstract fun bindTrashRepository(
        impl: com.acgcompass.data.repository.TrashRepositoryImpl,
    ): com.acgcompass.domain.repository.TrashRepository

    @Binds
    @Singleton
    abstract fun bindLocalTrash(
        impl: com.acgcompass.data.repository.TrashRepositoryImpl,
    ): com.acgcompass.data.repository.LocalTrash

    @Binds
    @Singleton
    abstract fun bindWishSyncQueue(
        impl: com.acgcompass.data.sync.BangumiWishSync,
    ): com.acgcompass.data.sync.WishSyncQueue

    @Binds
    @Singleton
    abstract fun bindImportRepository(impl: ImportRepositoryImpl): ImportRepository

    @Binds
    @Singleton
    abstract fun bindTimeMachineRepository(impl: TimeMachineRepositoryImpl): TimeMachineRepository

    @Binds
    @Singleton
    abstract fun bindTasteProfileRepository(impl: TasteProfileRepositoryImpl): TasteProfileRepository

    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository

    @Binds
    @Singleton
    abstract fun bindBroadcastScheduleRepository(impl: BroadcastScheduleRepositoryImpl): BroadcastScheduleRepository

    @Binds
    @Singleton
    abstract fun bindCacheRepository(impl: CacheRepositoryImpl): CacheRepository

    @Binds
    @Singleton
    abstract fun bindDetailRepository(impl: DetailRepositoryImpl): DetailRepository

    @Binds
    @Singleton
    abstract fun bindDetailInteractionRepository(impl: DetailInteractionRepositoryImpl): DetailInteractionRepository

    @Binds
    @Singleton
    abstract fun bindRouteMapRepository(impl: RouteMapRepositoryImpl): RouteMapRepository

    @Binds
    @Singleton
    abstract fun bindWorkFeatureRepository(impl: WorkFeatureRepositoryImpl): WorkFeatureRepository

    @Binds
    @Singleton
    abstract fun bindPersonalCollectionRepository(
        impl: com.acgcompass.data.repository.PersonalCollectionRepositoryImpl,
    ): com.acgcompass.domain.repository.PersonalCollectionRepository

    @Binds
    @Singleton
    abstract fun bindRecommendationExposureRepository(
        impl: com.acgcompass.data.repository.RecommendationExposureRepositoryImpl,
    ): com.acgcompass.domain.repository.RecommendationExposureRepository

    @Binds
    @Singleton
    abstract fun bindBangumiSummarySource(
        impl: com.acgcompass.data.repository.BangumiSummarySourceImpl,
    ): com.acgcompass.domain.repository.BangumiSummarySource

    @Binds
    @Singleton
    abstract fun bindDustStatusSync(
        impl: com.acgcompass.data.sync.DustStatusSyncImpl,
    ): com.acgcompass.domain.repository.DustStatusSync

    @Binds
    @Singleton
    abstract fun bindBackgroundTaskRepository(
        impl: com.acgcompass.data.tasks.BackgroundTaskRepositoryImpl,
    ): com.acgcompass.domain.repository.BackgroundTaskRepository

    @Binds
    @Singleton
    abstract fun bindTasteAnalysisController(impl: TasteAnalysisCoordinator): TasteAnalysisController

    @Binds
    @Singleton
    abstract fun bindTagClassificationController(impl: TagClassificationCoordinator): TagClassificationController

    @Binds
    @Singleton
    abstract fun bindTasteAlgorithmConfigSource(
        impl: com.acgcompass.data.taste.BuiltInTasteAlgorithmConfigSource,
    ): com.acgcompass.domain.taste.TasteAlgorithmConfigSource

    companion object {

        @Provides
        @Singleton
        fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

        @Provides
        @Singleton
        fun provideDataSourceOrchestrator(): DataSourceOrchestrator = DefaultDataSourceOrchestrator()

        @Provides
        @Singleton
        fun provideAggregateRatingsUseCase(): AggregateRatingsUseCase = AggregateRatingsUseCase()

        @Provides
        @Singleton
        fun provideTasteStatsCalculator(): TasteStatsCalculator = TasteStatsCalculator()

        @Provides
        @Singleton
        fun provideScoringHabitCalculator(): ScoringHabitCalculator = ScoringHabitCalculator()
    }
}
