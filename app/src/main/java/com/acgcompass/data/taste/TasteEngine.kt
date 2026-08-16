package com.acgcompass.data.taste

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.logging.RedactingLogger
import com.acgcompass.data.local.dao.TagDimensionDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.BuildTasteProfileUseCase
import com.acgcompass.domain.taste.ComputeTasteMatchUseCase
import com.acgcompass.domain.taste.TasteCategory
import com.acgcompass.domain.taste.TasteMatchResult
import com.acgcompass.domain.taste.TasteRefreshProgress
import com.acgcompass.domain.taste.TasteSample
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.domain.taste.WorkFeatureRepository
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 口味引擎的应用层编排器：把领域纯用例
 * （[BuildTasteProfileUseCase] / [ComputeTasteMatchUseCase]）与数据
 * （`user_collections` × [WorkFeatureRepository] 的 work_features 缓存）粘合，
 * **详情页口味匹配度**与**今晚看什么**共用同一画像与同一打分口径。
 *
 * 画像缓存于内存 [StateFlow]：
 * - [rebuildFromCache]：仅用已缓存特征重建（不联网），供冷启动 / 评分后自动刷新（快、零流量）。
 * - [refreshFull]：联网补齐缺失特征后重建（导入 / 同步后调用，一次性建好 work_features）。
 * - [score]：对单部候选评分（缺画像先按缓存重建；候选特征 best-effort 联网取）。
 *
 * 韧性：任一步骤失败都不抛、不伪造——画像不可用时 [score] 退化为低置信中性结果。
 */
@Singleton
class TasteEngine @Inject constructor(
    private val userCollectionDao: UserCollectionDao,
    private val workDao: WorkDao,
    private val workFeatureRepository: WorkFeatureRepository,
    private val buildProfile: BuildTasteProfileUseCase,
    private val computeMatch: ComputeTasteMatchUseCase,
    private val tagDimensionDao: TagDimensionDao,
    private val dispatchers: DispatcherProvider,
) {
    private val logger = RedactingLogger()

    /**
     * 读 `tag_dimensions` 缓存为「清洗后标签 → 口味维度」覆盖表，供画像构建 / 评分对本地兜底为题材的
     * 未知标签做更精确的升维分类。表为空 / 读失败 / 维度非法 → 返回空表，全回退本地规则（不阻塞、不伪造）。
     */
    private suspend fun loadTagOverrides(): Map<String, TasteCategory> = runCatching {
        tagDimensionDao.getAll()
            .mapNotNull { e -> TasteCategory.fromKey(e.dimension)?.let { e.tag to it } }
            .toMap()
    }.getOrDefault(emptyMap())

    private val profileState = MutableStateFlow<AdvancedTasteProfile?>(null)

    /** 当前口味画像流（`null` = 尚未构建）。详情页 / 推荐页可观察以驱动刷新。 */
    fun observeProfile(): StateFlow<AdvancedTasteProfile?> = profileState.asStateFlow()

    private val refreshProgressState = MutableStateFlow<TasteRefreshProgress?>(null)

    /** 联网分析进度流；`null` 表示当前没有分析任务。 */
    fun observeRefreshProgress(): StateFlow<TasteRefreshProgress?> = refreshProgressState.asStateFlow()

    /** 最近一次构建好的画像（同步访问）。 */
    val currentProfile: AdvancedTasteProfile? get() = profileState.value

    private val rebuildMutex = Mutex()

    /**
     * 本进程是否已尝试过「联网补齐 work_features」（[ensureReady] 用）：避免对暂无可用画像的用户
     * 每次进详情页 / 推荐页都重复联网拉取。导入页显式调用 [refreshFull] 同样会置位。
     */
    @Volatile
    private var networkFillAttempted = false

    /** 仅用本地缓存特征重建画像（不联网）。 */
    suspend fun rebuildFromCache(): AdvancedTasteProfile? = rebuild(networkBudget = 0)

    /** 联网补齐缺失 work_features 后重建画像（导入 / 同步后调用）。 */
    suspend fun refreshFull(): AdvancedTasteProfile? {
        networkFillAttempted = true
        return rebuild(networkBudget = MAX_NETWORK_FILL)
    }

    /**
     * 保障画像「就绪」（详情页 / 推荐页进入时调用）：
     * 1) 内存已有可用画像 → 直接返回；
     * 2) 否则先用已缓存特征**不联网**重建；
     * 3) 仍不可用时返回当前本地结果，不在详情页或推荐页隐式联网。
     */
    suspend fun ensureReady(): AdvancedTasteProfile? {
        profileState.value?.let { if (it.isUsable) return it }
        return rebuildFromCache()
    }

    private suspend fun rebuild(networkBudget: Int): AdvancedTasteProfile? = rebuildMutex.withLock {
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            val rated = userCollectionDao.getAll().filter { it.rating != null && it.sourceItemId.isNotBlank() }
            if (rated.isEmpty()) {
                val empty = AdvancedTasteProfile(generatedAt = now)
                profileState.value = empty
                return@withContext empty
            }
            val ids = rated.map { it.sourceItemId }.distinct()
            if (networkBudget > 0) {
                refreshProgressState.value = TasteRefreshProgress(
                    TasteRefreshProgress.Phase.FETCHING_RATED,
                    0,
                    ids.size,
                )
            }
            val features: Map<String, WorkFeature> = runCatching {
                workFeatureRepository.getFeatures(ids, networkBudget) { done, total ->
                    if (networkBudget > 0) {
                        refreshProgressState.value =
                            TasteRefreshProgress(TasteRefreshProgress.Phase.FETCHING_RATED, done, total)
                    }
                }
            }.getOrDefault(emptyMap())

            val samples = buildSamples(rated, features)
            if (samples.isEmpty() && profileState.value?.isUsable == true) {
                // 短暂的特征缓存缺口不能用空结果覆盖上一份有效画像。
                if (networkBudget > 0) refreshProgressState.value = null
                return@withContext profileState.value
            }
            // 候选池校准：以本地缓存的**未评分**作品特征全体为校准池，其 rawZ 中位定 μ，使未评分作品
            // 出分落在 50 分附近、按契合度拉开（详见 BuildTasteProfileUseCase.calibrate）。排除已评分作品
            // （它们走显式评分锚定，且会污染「典型未评分作品」中心）。池不足时 build 内部回退训练样本留一 z。
            val ratedIds = rated.mapTo(HashSet()) { it.sourceItemId }
            var calibrationPool = runCatching { workFeatureRepository.getCachedPool() }
                .getOrDefault(emptyList())
                .filter { it.subjectId !in ratedIds }
            // 联网重建时补足真实的未评分校准池，避免仅用已评分样本产生幸存者偏差。
            // 补齐失败时保留原池，并由构建器使用训练样本校准作为回退。
            if (networkBudget > 0 && calibrationPool.size < CALIB_POOL_TARGET) {
                runCatching {
                    backfillCalibrationPool(ratedIds, calibrationPool.mapTo(HashSet()) { it.subjectId })
                }
                calibrationPool = runCatching { workFeatureRepository.getCachedPool() }
                    .getOrDefault(emptyList())
                    .filter { it.subjectId !in ratedIds }
            }
            if (networkBudget > 0) {
                refreshProgressState.value = TasteRefreshProgress(TasteRefreshProgress.Phase.BUILDING)
            }
            val profile = buildProfile(samples, now, calibrationPool, loadTagOverrides())
            // 显式评分锚定必须覆盖「全部已评分作品」——含特征未取到、未进 50 样本者，否则这些作品
            // 在详情页拿不到显式评分锚（rating 查不到）→ 仍会塌到个位数。用完整评分表覆盖 ratedSubjectScores。
            val allRatedScores = rated.mapNotNull { c -> c.rating?.let { c.sourceItemId to it } }.toMap()
            val finalProfile = if (allRatedScores.isEmpty()) {
                profile
            } else {
                profile.copy(
                    ratedSubjectScores = allRatedScores,
                )
            }
            profileState.value = finalProfile
            if (networkBudget > 0) refreshProgressState.value = null
            finalProfile
        }
    }

    /**
     * 冷启动候选池补齐：从本地发现池取未评分且尚未缓存的动画 id，
     * best-effort 联网补齐其 [WorkFeature]（复用 [WorkFeatureRepository.getFeatures] 的取 + 落库）。
     * 只拉「补足到 [CALIB_POOL_TARGET] 的缺口」，避免无谓联网；无候选或失败即静默返回（回退 LOO 校准）。
     */
    private suspend fun backfillCalibrationPool(ratedIds: Set<String>, cachedNonRatedIds: Set<String>) {
        val deficit = CALIB_POOL_TARGET - cachedNonRatedIds.size
        if (deficit <= 0) return
        val candidates = workDao.getIdsByMediaType(MediaType.ANIME.name, CALIB_POOL_TARGET * 4)
            .asSequence()
            .filter { it.isNotBlank() && it.toIntOrNull() != null }
            .filter { it !in ratedIds && it !in cachedNonRatedIds }
            .distinct()
            .take(deficit)
            .toList()
        if (candidates.isEmpty()) return
        refreshProgressState.value = TasteRefreshProgress(TasteRefreshProgress.Phase.FETCHING_POOL, 0, candidates.size)
        workFeatureRepository.getFeatures(candidates, networkBudget = candidates.size) { done, total ->
            refreshProgressState.value = TasteRefreshProgress(TasteRefreshProgress.Phase.FETCHING_POOL, done, total)
        }
    }

    /**
     * 对候选作品计算口味匹配度。画像为空时先按缓存重建；候选特征缓存缺失时按 [allowNetwork] 决定是否联网。
     * 无任何可用数据返回 `null`（调用方据此显示「暂无数据」，不伪造）。
     */
    suspend fun score(subjectId: String, userRating: Int? = null, allowNetwork: Boolean = true): TasteMatchResult? =
        withContext(dispatchers.io) {
            if (subjectId.isBlank()) return@withContext null
            val profile = profileState.value ?: rebuildFromCache()
            if (profile == null || !profile.isUsable) return@withContext null
            val feature = runCatching { workFeatureRepository.getFeature(subjectId, allowNetwork) }
                .getOrNull() ?: return@withContext null
            computeMatch(feature, profile, userRating, loadTagOverrides())
        }

    /** BASIC 特征就绪后立即出分，低优先级扩展特征写入时再精化结果。 */
    fun observeScore(
        subjectId: String,
        userRating: Int? = null,
        allowNetwork: Boolean = true,
    ): Flow<TasteMatchResult?> = channelFlow {
        if (subjectId.isBlank()) {
            send(null)
            return@channelFlow
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var basicLogged = false
        var extendedLogged = false
        launch {
            workFeatureRepository.observeFeature(subjectId)
                .filterNotNull()
                .collect { feature ->
                    val profile = profileState.value ?: rebuildFromCache()
                    if (profile == null || !profile.isUsable) {
                        send(null)
                    } else {
                        send(computeMatch(feature, profile, userRating, loadTagOverrides()))
                    }
                    val elapsed = android.os.SystemClock.elapsedRealtime() - startedAt
                    if (!basicLogged) {
                        basicLogged = true
                        logger.d(TIMING_LOG_TAG, "basic_match_ready_ms=$elapsed")
                    }
                    if (feature.completeness == com.acgcompass.domain.taste.WorkFeatureCompleteness.EXTENDED &&
                        !extendedLogged
                    ) {
                        extendedLogged = true
                        logger.d(TIMING_LOG_TAG, "extended_match_ready_ms=$elapsed")
                    }
                }
        }
        val feature = runCatching { workFeatureRepository.getFeature(subjectId, allowNetwork) }.getOrNull()
        if (feature == null) send(null)
        if (allowNetwork && feature != null &&
            feature.completeness != com.acgcompass.domain.taste.WorkFeatureCompleteness.EXTENDED
        ) {
            launch { runCatching { workFeatureRepository.refresh(subjectId) } }
        }
    }.distinctUntilChanged()

    /**
     * 批量评分（今晚看什么召回后精排用）：对 [subjectIds] 在 [networkBudget] 限额内补齐特征后逐一打分。
     * 画像不可用时返回空表（调用方回退旧打分）。仅返回成功取到特征者，键为 subjectId。
     */
    suspend fun scoreBatch(subjectIds: List<String>, networkBudget: Int = 0): Map<String, TasteMatchResult> =
        withContext(dispatchers.io) {
            if (subjectIds.isEmpty()) return@withContext emptyMap()
            val profile = profileState.value ?: rebuildFromCache()
            if (profile == null || !profile.isUsable) return@withContext emptyMap()
            val features = runCatching {
                workFeatureRepository.getFeatures(subjectIds.distinct(), networkBudget)
            }.getOrDefault(emptyMap())
            val tagOverrides = loadTagOverrides()
            features.mapValues { (_, f) -> computeMatch(f, profile, null, tagOverrides) }
        }

    /**
     * 只读本地缓存的作品特征（不联网）。「今晚看什么」用其中的标签标注人数判断标签代表性；
     * 未缓存的作品不在返回表里（调用方退回只看标签顺序）。
     */
    suspend fun cachedFeatures(subjectIds: List<String>): Map<String, WorkFeature> = withContext(dispatchers.io) {
        if (subjectIds.isEmpty()) {
            emptyMap()
        } else {
            runCatching { workFeatureRepository.getFeatures(subjectIds.distinct(), 0) }.getOrDefault(emptyMap())
        }
    }

    /** 解析单条收藏的「评定时间」毫秒：优先 Bangumi `sourceUpdatedAt`，回退本地 `updatedAt`。 */
    private fun timestampOf(c: UserCollectionEntity): Long = parseIsoMillis(c.sourceUpdatedAt) ?: c.updatedAt

    private fun buildSamples(
        rated: List<UserCollectionEntity>,
        features: Map<String, WorkFeature>,
    ): List<TasteSample> = rated.mapNotNull { collection ->
        val feature = features[collection.sourceItemId] ?: return@mapNotNull null
        TasteSample(
            subjectId = collection.sourceItemId,
            rating = collection.rating,
            comment = collection.comment,
            updatedAtMillis = timestampOf(collection),
            feature = feature,
        )
    }

    private fun parseIsoMillis(iso: String?): Long? {
        val s = iso?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { Instant.parse(s).toEpochMilli() }.getOrNull()
    }

    private companion object {
        /** 单次 refreshFull 联网补齐 work_features 的上限（覆盖典型 50 样本画像，避免无限拉取）。 */
        const val MAX_NETWORK_FILL: Int = 60

        /**
         * 冷启动候选池目标规模；联网重建时补齐到此值。
         * 取 40（> [BuildTasteProfileUseCase] 的 CALIB_MIN_POOL=20 留余量），使 μ=median(池) 稳定可信。
         */
        const val CALIB_POOL_TARGET: Int = 40
        const val TIMING_LOG_TAG = "TasteMatchTiming"
    }
}
