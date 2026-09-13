package com.acgcompass.data.taste

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.ApplicationScope
import com.acgcompass.data.local.dao.BackgroundTaskStateDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.BackgroundTaskStateEntity
import com.acgcompass.data.sync.BangumiSyncManager
import com.acgcompass.data.sync.TasteProfileAutoUpdater
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.TagDimensionSummary
import com.acgcompass.domain.taste.TasteAnalysisController
import com.acgcompass.domain.taste.TasteImportResult
import com.acgcompass.domain.taste.TasteLocalAnalysisPhase
import com.acgcompass.domain.taste.TasteLocalAnalysisState
import com.acgcompass.domain.taste.TasteMatchResult
import com.acgcompass.domain.taste.TasteProcessingSummary
import com.acgcompass.domain.taste.TasteRefreshProgress
import com.acgcompass.domain.taste.TasteRefreshResult
import com.acgcompass.domain.taste.WorkFeature
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
@Suppress("LongParameterList")
class TasteAnalysisCoordinator @Inject constructor(
    private val bangumiSyncManager: BangumiSyncManager,
    private val userCollectionDao: UserCollectionDao,
    private val tasteEngine: TasteEngine,
    private val aiTagClassifier: AiTagClassifier,
    private val tasteProfileAutoUpdater: TasteProfileAutoUpdater,
    private val taskStateDao: BackgroundTaskStateDao,
    @ApplicationScope scope: CoroutineScope,
) : TasteAnalysisController {
    override val refreshProgress: StateFlow<TasteRefreshProgress?> = tasteEngine.observeRefreshProgress()
    override val advancedProfile: StateFlow<AdvancedTasteProfile?> = tasteEngine.observeProfile()
    override val localAnalysisState: StateFlow<TasteLocalAnalysisState> = taskStateDao
        .observe(LOCAL_ANALYSIS_TASK_KEY)
        .map { it.toTasteLocalAnalysisState() }
        .stateIn(scope, SharingStarted.Eagerly, TasteLocalAnalysisState())

    override suspend fun isBangumiConfigured(): Boolean = bangumiSyncManager.isConfigured()

    override suspend fun importFromBangumi(): TasteImportResult = if (!bangumiSyncManager.isConfigured()) {
        TasteImportResult.NotConfigured
    } else {
        when (val sync = bangumiSyncManager.syncCollections()) {
            is AppResult.Failure -> TasteImportResult.SyncFailed(sync.error.cause)
            is AppResult.Success -> importLocalCollections()
        }
    }

    private suspend fun importLocalCollections(): TasteImportResult {
        val collections = userCollectionDao.getAll()
        if (collections.isEmpty()) return TasteImportResult.NoData
        return if (tasteProfileAutoUpdater.refreshNow()) {
            TasteImportResult.Completed(collections.size)
        } else {
            TasteImportResult.AnalysisFailed
        }
    }

    override suspend fun ensureCurrentProfile() {
        tasteProfileAutoUpdater.ensureCurrent()
    }

    override suspend fun refresh(): TasteRefreshResult =
        if (tasteProfileAutoUpdater.refreshNow()) TasteRefreshResult.COMPLETED else TasteRefreshResult.FAILED

    override suspend fun dimensionSummary(): TagDimensionSummary = aiTagClassifier.dimensionSummary()

    override suspend fun pendingTagCount(): Int = aiTagClassifier.pendingCount()

    override suspend fun processingSummary(): TasteProcessingSummary = aiTagClassifier.processingSummary()

    override suspend fun ensureReady() {
        tasteEngine.ensureReady()
    }

    override suspend fun rebuildFromCache() {
        tasteEngine.rebuildFromCache()
    }

    override suspend fun score(subjectId: String, userRating: Int?, allowNetwork: Boolean): TasteMatchResult? =
        tasteEngine.score(subjectId, userRating, allowNetwork)

    override fun observeScore(
        subjectId: String,
        userRating: Int?,
        allowNetwork: Boolean,
        fallback: WorkFeature?,
    ): Flow<TasteMatchResult?> = tasteEngine.observeScore(subjectId, userRating, allowNetwork, fallback)

    private companion object {
        const val LOCAL_ANALYSIS_TASK_KEY = "local_taste_analysis"
    }
}

internal fun BackgroundTaskStateEntity?.toTasteLocalAnalysisState() = TasteLocalAnalysisState(
    phase = when (this?.phase) {
        "COLLECTING" -> TasteLocalAnalysisPhase.COLLECTING
        "BUILDING" -> TasteLocalAnalysisPhase.BUILDING
        else -> TasteLocalAnalysisPhase.IDLE
    },
    completed = this?.completed?.coerceAtLeast(0) ?: 0,
    total = this?.total?.coerceAtLeast(0) ?: 0,
    lastSuccessfulAt = this?.completedAt,
    error = this?.error,
    retryAt = this?.retryAt,
)
