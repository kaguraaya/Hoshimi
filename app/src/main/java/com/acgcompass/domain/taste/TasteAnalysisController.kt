package com.acgcompass.domain.taste

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

@Suppress("TooManyFunctions")
interface TasteAnalysisController {
    val refreshProgress: StateFlow<TasteRefreshProgress?>
    val advancedProfile: StateFlow<AdvancedTasteProfile?>
    val localAnalysisState: StateFlow<TasteLocalAnalysisState>

    /** 已评分作品的回测（重建画像后在后台算，算完前为 null）。 */
    val backtest: StateFlow<TasteBacktest?> get() = kotlinx.coroutines.flow.MutableStateFlow(null)

    suspend fun isBangumiConfigured(): Boolean

    suspend fun importFromBangumi(): TasteImportResult

    suspend fun ensureCurrentProfile()

    suspend fun refresh(): TasteRefreshResult

    suspend fun dimensionSummary(): TagDimensionSummary

    suspend fun pendingTagCount(): Int

    suspend fun processingSummary(): TasteProcessingSummary

    suspend fun ensureReady()

    suspend fun rebuildFromCache()

    /** 把用户此刻看到的预测记进预测账本（只写本地、不联网；失败不影响调用方）。 */
    suspend fun recordPredictions(context: PredictionContext, subjectIds: Collection<String>) = Unit

    suspend fun score(subjectId: String, userRating: Int?, allowNetwork: Boolean): TasteMatchResult?

    /** [fallback]：取不到 Bangumi 特征时用来出分的临时特征（作品自己的标签与评分）。 */
    fun observeScore(
        subjectId: String,
        userRating: Int?,
        allowNetwork: Boolean,
        fallback: WorkFeature? = null,
    ): Flow<TasteMatchResult?>
}

enum class TasteLocalAnalysisPhase {
    IDLE,
    COLLECTING,
    BUILDING,
}

data class TasteLocalAnalysisState(
    val phase: TasteLocalAnalysisPhase = TasteLocalAnalysisPhase.IDLE,
    val completed: Int = 0,
    val total: Int = 0,
    val lastSuccessfulAt: Long? = null,
    val error: String? = null,
    val retryAt: Long? = null,
) {
    val isRunning: Boolean
        get() = phase == TasteLocalAnalysisPhase.COLLECTING || phase == TasteLocalAnalysisPhase.BUILDING

    val fraction: Float
        get() = if (total <= 0) 0f else (completed.toFloat() / total).coerceIn(0f, 1f)
}

interface TagClassificationController {
    val state: StateFlow<TagClassificationState>

    fun start()

    fun stop()

    /** Consumes the terminal result notification while retaining durable plan counters. */
    fun acknowledgeLastResult()

    suspend fun manualReviewItems(): List<ManualTagReview>

    suspend fun resolveManual(tags: Set<String>, category: TasteCategory)
}

data class ManualTagReview(
    val tag: String,
    val proposedCategory: TasteCategory?,
    val confidence: Float?,
)

sealed interface TasteImportResult {
    data class Completed(val importedCount: Int) : TasteImportResult

    data class SyncFailed(val message: String?) : TasteImportResult

    data object NotConfigured : TasteImportResult

    data object NoData : TasteImportResult

    data object AnalysisFailed : TasteImportResult
}

enum class TasteRefreshResult {
    COMPLETED,
    FAILED,
}

data class TasteRefreshProgress(
    val phase: Phase,
    val current: Int = 0,
    val total: Int = 0,
) {
    enum class Phase { FETCHING_RATED, FETCHING_POOL, BUILDING }

    val fraction: Float get() = if (total > 0) (current.toFloat() / total).coerceIn(0f, 1f) else 0f
    val isDeterminate: Boolean get() = total > 0
}

data class TagClassifyProgress(
    val done: Int,
    val total: Int,
    val batchIndex: Int,
    val batchCount: Int,
    val phase: TagClassificationPhase,
)

data class TagDimensionSummary(val total: Int, val buckets: List<TagDimensionBucket>) {
    val isEmpty: Boolean get() = total <= 0 || buckets.isEmpty()
}

data class TasteProcessingSummary(
    /**
     * 本地作品库（work_features 全表）的部数 / 其中有完整标签的部数。除你的作品外，还包括浏览过的
     * 榜单、放送表、搜索结果、探索队列里的作品，以及匹配度参照池，后台会逐批补齐它们的资料。
     */
    val localFeatureCount: Int = 0,
    val verifiedFeatureCount: Int = 0,
    /** 你的作品（收藏 + 待补池）里已有本地资料的部数。 */
    val personalFeatureCount: Int = 0,
    /** 你的作品的标签去重后的个数（已排除噪声标签）。 */
    val localUniqueTags: Int = 0,
    val aiTerminalUniqueTags: Int = 0,
    val cumulativeUniqueTags: Int = 0,
    val coveredPriorityWorks: Int = 0,
    val totalPriorityWorks: Int = 0,
) {
    val coverageFraction: Float
        get() = if (totalPriorityWorks == 0) 0f else coveredPriorityWorks.toFloat() / totalPriorityWorks

    /** 本地作品库里不属于你的作品的部数。 */
    val otherFeatureCount: Int
        get() = (localFeatureCount - personalFeatureCount).coerceAtLeast(0)
}

data class TagDimensionBucket(val category: TasteCategory, val count: Int)
