package com.acgcompass.data.taste

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.logging.RedactingLogger
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.RecommendationExposureDao
import com.acgcompass.data.local.dao.TagDimensionDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.BuildTasteProfileUseCase
import com.acgcompass.domain.taste.ComputeTasteMatchUseCase
import com.acgcompass.domain.taste.DivergenceOptions
import com.acgcompass.domain.taste.ImplicitSignal
import com.acgcompass.domain.taste.LocalRankedWork
import com.acgcompass.domain.taste.LocalRanking
import com.acgcompass.domain.taste.TasteAlgorithmConfigSource
import com.acgcompass.domain.taste.TasteCategory
import com.acgcompass.domain.taste.TasteMatchBranch
import com.acgcompass.domain.taste.TasteMatchResult
import com.acgcompass.domain.taste.TasteModelVersion
import com.acgcompass.domain.taste.TasteRefreshProgress
import com.acgcompass.domain.taste.TasteSample
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.domain.taste.WorkFeatureRepository
import java.time.Instant
import java.time.OffsetDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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
@Suppress("LongParameterList") // Hilt 构造注入。
@Singleton
class TasteEngine @Inject constructor(
    private val userCollectionDao: UserCollectionDao,
    private val backlogDao: com.acgcompass.data.local.dao.BacklogDao,
    private val workDao: WorkDao,
    private val workFeatureRepository: WorkFeatureRepository,
    private val buildProfile: BuildTasteProfileUseCase,
    private val computeMatch: ComputeTasteMatchUseCase,
    private val tagDimensionDao: TagDimensionDao,
    private val exposureDao: RecommendationExposureDao,
    private val ratingDao: RatingDao,
    private val settingsDataStore: SettingsDataStore,
    private val algorithmConfig: TasteAlgorithmConfigSource,
    private val dispatchers: DispatcherProvider,
    @com.acgcompass.core.common.ApplicationScope private val appScope: kotlinx.coroutines.CoroutineScope,
) {
    private val logger = RedactingLogger()

    /**
     * 读 `tag_dimensions` 缓存为「清洗后标签 → 口味维度」覆盖表，供画像构建 / 评分对本地兜底为题材的
     * 未知标签做更精确的升维分类。表为空 / 读失败 / 维度非法 → 返回空表，全回退本地规则（不阻塞、不伪造）。
     */
    private suspend fun loadTagOverrides(): Map<String, TasteCategory> = runCatchingCancellable {
        tagDimensionDao.getAll()
            .mapNotNull { e -> TasteCategory.fromKey(e.dimension)?.let { e.tag to it } }
            .toMap()
    }.getOrDefault(emptyMap())

    private val profileState = MutableStateFlow<AdvancedTasteProfile?>(null)

    /** 当前口味画像流（`null` = 尚未构建）。详情页 / 推荐页可观察以驱动刷新。 */
    fun observeProfile(): StateFlow<AdvancedTasteProfile?> = profileState.asStateFlow()

    private val backtestState = MutableStateFlow<com.acgcompass.domain.taste.TasteBacktest?>(null)
    private var backtestJob: kotlinx.coroutines.Job? = null

    /**
     * 已评分作品的回测（[com.acgcompass.domain.taste.TasteBacktest]）：每次重建画像后在后台按 5 折重算，
     * 期间为 null。详情页、为你排行的「回测匹配度 / 回测预计评分」和口味画像页的关联度都读它。
     */
    fun observeBacktest(): StateFlow<com.acgcompass.domain.taste.TasteBacktest?> = backtestState.asStateFlow()

    /** 重建画像后启动回测；上一次还没算完就取消。回测只读输入，不改画像，不影响各页面出分。 */
    private fun startBacktest(inputs: com.acgcompass.domain.taste.TasteBuildInputs) {
        backtestJob?.cancel()
        backtestState.value = null
        backtestJob = appScope.launch(dispatchers.default) {
            val job = coroutineContext[kotlinx.coroutines.Job]
            val result = runCatchingCancellable {
                com.acgcompass.domain.taste.TasteBacktest.run(inputs, buildProfile, computeMatch) {
                    job?.ensureActive()
                }
            }.getOrNull()
            if (result != null) backtestState.value = result
        }
    }

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
            val features: Map<String, WorkFeature> = runCatchingCancellable {
                workFeatureRepository.getFeatures(ids, networkBudget) { done, total ->
                    if (networkBudget > 0) {
                        refreshProgressState.value =
                            TasteRefreshProgress(TasteRefreshProgress.Phase.FETCHING_RATED, done, total)
                    }
                }
            }.getOrDefault(emptyMap())

            val samples = buildSamples(rated, withCommunityFallback(features))
            if (samples.isEmpty() && profileState.value?.isUsable == true) {
                // 短暂的特征缓存缺口不能用空结果覆盖上一份有效画像。
                if (networkBudget > 0) refreshProgressState.value = null
                return@withContext profileState.value
            }
            // 候选池校准：以本地缓存的**未评分**作品特征全体为校准池，其 rawZ 中位定 μ，使未评分作品
            // 出分落在 50 分附近、按契合度拉开（详见 BuildTasteProfileUseCase.calibrate）。排除已评分作品
            // （它们走显式评分锚定，且会污染「典型未评分作品」中心）。池不足时 build 内部回退训练样本留一 z。
            val ratedIds = rated.mapTo(HashSet()) { it.sourceItemId }
            var calibrationPool = runCatchingCancellable { workFeatureRepository.getCachedPool() }
                .getOrDefault(emptyList())
                .filter { it.subjectId !in ratedIds }
            // 联网重建时补足真实的未评分校准池，避免仅用已评分样本产生幸存者偏差。
            // 补齐失败时保留原池，并由构建器使用训练样本校准作为回退。
            if (networkBudget > 0 && calibrationPool.size < CALIB_POOL_TARGET) {
                runCatchingCancellable {
                    backfillCalibrationPool(ratedIds, calibrationPool.mapTo(HashSet()) { it.subjectId })
                }
                calibrationPool = runCatchingCancellable { workFeatureRepository.getCachedPool() }
                    .getOrDefault(emptyList())
                    .filter { it.subjectId !in ratedIds }
            }
            if (networkBudget > 0) {
                refreshProgressState.value = TasteRefreshProgress(TasteRefreshProgress.Phase.BUILDING)
            }
            val (profile, inputs) = buildWithImplicitSignals(samples, now, calibrationPool, ratedIds)
            // 显式评分锚定必须覆盖「全部已评分作品」——含特征未取到、未进 50 样本者，否则这些作品
            // 在详情页拿不到显式评分锚（rating 查不到）→ 仍会塌到个位数。用完整评分表覆盖 ratedSubjectScores。
            val allRatedScores = rated.mapNotNull { c -> c.rating?.let { c.sourceItemId to it } }.toMap()
            val finalProfile = withMatchBranch(profile.withRatedScores(allRatedScores))
            profileState.value = finalProfile
            startBacktest(inputs)
            if (networkBudget > 0) refreshProgressState.value = null
            finalProfile
        }
    }

    private suspend fun buildWithImplicitSignals(
        samples: List<TasteSample>,
        now: Long,
        calibrationPool: List<WorkFeature>,
        ratedIds: Set<String>,
    ): Pair<AdvancedTasteProfile, com.acgcompass.domain.taste.TasteBuildInputs> {
        val options = DivergenceOptions.DEFAULT
        val inputs = com.acgcompass.domain.taste.TasteBuildInputs(
            samples = samples,
            now = now,
            calibrationPool = calibrationPool,
            tagOverrides = loadTagOverrides(),
            implicitSignals = runCatchingCancellable { loadImplicitSignals(ratedIds, now) }.getOrDefault(emptyList()),
            options = options,
        )
        val profile = buildProfile(
            inputs.samples,
            inputs.now,
            inputs.calibrationPool,
            inputs.tagOverrides,
            implicitSignals = inputs.implicitSignals,
            divergenceOptions = inputs.options,
            // 另一版模型只用来往预测账本里记对照，不参与展示。
            shadowOptions = DivergenceOptions.DEFAULT.takeIf { options == DivergenceOptions.LEGACY }
                ?: DivergenceOptions.LEGACY,
        )
        return profile to inputs
    }

    /**
     * 收集隐式兴趣信号：未评分收藏的状态（想看 / 在看 / 搁置 / 抛弃）、待补池与探索队列的左右滑。
     * 只用本地已缓存的作品特征（不联网）；没有特征的作品跳过。
     */
    private suspend fun loadImplicitSignals(ratedIds: Set<String>, now: Long): List<ImplicitSignal> {
        val kinds = LinkedHashMap<String, ImplicitSignal.Kind>()
        // 探索队列：加入待补池 = 感兴趣；暂不 = 轻微的不感兴趣（原因未知，权重最低）。
        exposureDao.exposuresInContextSince(EXPLORE_CONTEXT, now - IMPLICIT_WINDOW_MS).forEach { e ->
            when {
                e.clickedAt != null -> kinds[e.subjectId] = ImplicitSignal.Kind.EXPLORE_LIKED
                e.dismissedAt != null -> kinds[e.subjectId] = ImplicitSignal.Kind.EXPLORE_SKIPPED
            }
        }
        // 加进待补池 = 想看（只认 Bangumi 条目号，作品资料按它存）。
        backlogDao.getAll().map { it.workId }.filter { it.toIntOrNull() != null }
            .forEach { kinds[it] = ImplicitSignal.Kind.WISH }
        // 收藏状态比滑动、待补池更明确，后写入以覆盖同一作品的其它信号。
        userCollectionDao.getAll()
            .filter { it.rating == null && it.sourceItemId.isNotBlank() }
            .forEach { c -> statusSignal(c.status)?.let { kinds[c.sourceItemId] = it } }
        val ids = kinds.keys.filter { it !in ratedIds }
        if (ids.isEmpty()) return emptyList()
        val features = workFeatureRepository.getFeatures(ids, 0)
        return ids.mapNotNull { id -> features[id]?.let { ImplicitSignal(it, kinds.getValue(id)) } }
    }

    private fun statusSignal(status: String?): ImplicitSignal.Kind? = when (status) {
        "想看" -> ImplicitSignal.Kind.WISH
        "在看" -> ImplicitSignal.Kind.WATCHING
        "搁置" -> ImplicitSignal.Kind.ON_HOLD
        "抛弃" -> ImplicitSignal.Kind.DROPPED
        else -> null
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
            val feature = runCatchingCancellable { workFeatureRepository.getFeature(subjectId, allowNetwork) }
                .getOrNull() ?: return@withContext null
            computeMatch(feature, profile, userRating, loadTagOverrides())
        }

    private suspend fun scoreWith(feature: WorkFeature, userRating: Int?): TasteMatchResult? {
        val profile = profileState.value ?: rebuildFromCache()
        return if (profile == null || !profile.isUsable) {
            null
        } else {
            computeMatch(
                feature,
                profile,
                userRating,
                loadTagOverrides(),
            )
        }
    }

    /**
     * 首次没取到特征（限流排队 / 网络抖动）时自动重试几次；取到后由 [observeScore] 里的
     * observeFeature 收集并出分。页面上「正在获取」的提示因此真的会自己完成，而不是要用户退出重进。
     */
    private suspend fun retryFeature(subjectId: String): WorkFeature? {
        for (waitMs in FEATURE_RETRY_DELAYS_MS) {
            delay(waitMs)
            val feature = runCatchingCancellable { workFeatureRepository.getFeature(subjectId, true) }.getOrNull()
            if (feature != null) return feature
        }
        return null
    }

    /** BASIC 特征就绪后立即出分，低优先级扩展特征写入时再精化结果。 */
    fun observeScore(
        subjectId: String,
        userRating: Int? = null,
        allowNetwork: Boolean = true,
        /** 取不到 Bangumi 特征时用来出分的临时特征（见 `toFallbackFeature`）。 */
        fallback: WorkFeature? = null,
    ): Flow<TasteMatchResult?> = channelFlow {
        if (subjectId.isBlank()) {
            send(null)
            return@channelFlow
        }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        var basicLogged = false
        var extendedLogged = false
        val latest = java.util.concurrent.atomic.AtomicReference<TasteMatchResult?>(null)
        sendBacktestUpdates(subjectId, rated = userRating != null, latest)
        launch {
            workFeatureRepository.observeFeature(subjectId)
                .filterNotNull()
                .collect { feature ->
                    val profile = profileState.value ?: rebuildFromCache()
                    if (profile == null || !profile.isUsable) {
                        send(null)
                    } else {
                        val match = computeMatch(feature, profile, userRating, loadTagOverrides())
                        latest.set(match)
                        send(withBacktest(match, subjectId, profile.matchBranch))
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
        val usableFallback = fallback?.takeIf { it.tagCounts.isNotEmpty() }
        var feature = loadFeatureOrFallback(subjectId, allowNetwork) {
            usableFallback?.let { send(scoreWith(it, userRating)) }
        }
        if (feature == null) {
            if (usableFallback == null) send(null)
            if (allowNetwork) feature = retryFeature(subjectId)
        }
        if (allowNetwork && feature?.needsExtendedRefresh() == true) {
            launch { runCatchingCancellable { workFeatureRepository.refresh(subjectId) } }
        }
    }.distinctUntilChanged()

    /** 已评分作品：回测算完后把「回测匹配度 / 回测预计评分」补到最近一次结果上。 */
    private fun kotlinx.coroutines.channels.ProducerScope<TasteMatchResult?>.sendBacktestUpdates(
        subjectId: String,
        rated: Boolean,
        latest: java.util.concurrent.atomic.AtomicReference<TasteMatchResult?>,
    ) {
        if (!rated) return
        launch {
            backtestState.collect {
                val branch = profileState.value?.matchBranch ?: return@collect
                latest.get()?.let { match -> send(withBacktest(match, subjectId, branch)) }
            }
        }
    }

    private fun withBacktest(match: TasteMatchResult, subjectId: String, branch: TasteMatchBranch): TasteMatchResult =
        match.copy(backtest = backtestState.value?.view(subjectId, branch))

    /**
     * 本地没有特征时先用作品自己的标签出分，不等联网（限流排队时可能要等十几秒）；
     * 真正的特征到了会由 [observeScore] 里的收集自动替换。
     */
    private suspend fun loadFeatureOrFallback(
        subjectId: String,
        allowNetwork: Boolean,
        emitFallback: suspend () -> Unit,
    ): WorkFeature? {
        val cached = runCatchingCancellable { workFeatureRepository.getFeature(subjectId, false) }.getOrNull()
        if (cached != null) return cached
        emitFallback()
        return if (allowNetwork) {
            runCatchingCancellable {
                workFeatureRepository.getFeature(subjectId, true)
            }.getOrNull()
        } else {
            null
        }
    }

    /**
     * 批量评分（今晚看什么召回、探索队列组批用）：对 [subjectIds] 在 [networkBudget] 限额内补齐特征后逐一打分。
     * 画像不可用时返回空表。仅返回取到特征者，键为 subjectId。
     *
     * 提速而不改结果：
     * - 结果按「画像 + 标签覆盖表」记忆：画像没重建、覆盖表没变时，同一部作品不重复算（再次进探索队列几乎瞬间出结果）；
     * - 没算过的分块并行计算（纯函数，结果与串行完全一致）；
     * - 不算「题材相近的已评分作品」（只有详情页用）。
     */
    suspend fun scoreBatch(subjectIds: List<String>, networkBudget: Int = 0): Map<String, TasteMatchResult> =
        withContext(dispatchers.io) {
            if (subjectIds.isEmpty()) return@withContext emptyMap()
            val profile = profileState.value ?: rebuildFromCache()
            if (profile == null || !profile.isUsable) return@withContext emptyMap()
            val features = runCatchingCancellable {
                workFeatureRepository.getFeatures(subjectIds.distinct(), networkBudget)
            }.getOrDefault(emptyMap())
            scoreLoaded(profile, features)
        }

    /**
     * 和 [scoreBatch] 一样打分，但直接用调用方已经读出来的特征（今晚看什么还要拿同一批特征做意图匹配，
     * 不必再读一遍库）。画像不可用时返回空表。
     */
    suspend fun scoreFeatures(features: Map<String, WorkFeature>): Map<String, TasteMatchResult> =
        withContext(dispatchers.io) {
            if (features.isEmpty()) return@withContext emptyMap()
            val profile = profileState.value ?: rebuildFromCache()
            if (profile == null || !profile.isUsable) return@withContext emptyMap()
            scoreLoaded(profile, features)
        }

    private suspend fun scoreLoaded(
        profile: AdvancedTasteProfile,
        features: Map<String, WorkFeature>,
    ): Map<String, TasteMatchResult> {
        val tagOverrides = loadTagOverrides()
        val memo = batchMemoFor(profile, tagOverrides)
        val todo = features.filterKeys { !memo.containsKey(it) }
        if (todo.isNotEmpty()) {
            val chunkSize = (todo.size / PARALLEL_CHUNKS + 1).coerceAtLeast(MIN_CHUNK)
            coroutineScope {
                todo.entries.chunked(chunkSize).map { chunk ->
                    async(dispatchers.default) {
                        chunk.forEach { (id, f) ->
                            memo[id] = computeMatch(f, profile, null, tagOverrides, withNeighbors = false)
                        }
                    }
                }.awaitAll()
            }
        }
        return features.keys.mapNotNull { id -> memo[id]?.let { id to it } }.toMap()
    }

    /**
     * 给本地作品资料库里的全部作品（已评分的除外，最多 [limit] 部）算口味匹配度并从高到低排序，不联网。
     * 只算分数（不算推荐理由和相近作品），分块并行。供以后的「口味匹配度排行」使用。
     */
    suspend fun rankLocalPool(limit: Int = LOCAL_RANK_LIMIT): List<Pair<String, Int>> = withContext(dispatchers.io) {
        val profile = profileState.value ?: rebuildFromCache()
        if (profile == null || !profile.isUsable) return@withContext emptyList()
        val rated = profile.ratedSubjectScores.keys
        val pool = runCatchingCancellable { workFeatureRepository.getCachedPool(limit) }.getOrDefault(emptyList())
            .filter { it.subjectId !in rated }
        val overrides = loadTagOverrides()
        val chunkSize = (pool.size / PARALLEL_CHUNKS + 1).coerceAtLeast(MIN_CHUNK)
        coroutineScope {
            pool.chunked(chunkSize).map { chunk ->
                async(dispatchers.default) {
                    chunk.map { f ->
                        val result =
                            computeMatch(f, profile, null, overrides, withNeighbors = false, withReasons = false)
                        f.subjectId to result.score
                    }
                }
            }.awaitAll().flatten()
        }.sortedByDescending { it.second }
    }

    /**
     * 口味排行用：本地作品库里的动画全部算匹配度并排序（只算分；评过分的作品带上你的评分，和详情页一致），
     * 再给没评过分的前 [detailedUnrated] 部、评过分的前 [detailedRated] 部补上推荐理由和预计评分。
     * 两组分开取，避免评过分的作品把待发现的作品挤出前列。不联网；画像不可用时返回 null。
     */
    suspend fun rankLocalPoolDetailed(
        detailedUnrated: Int = RANK_DETAILED,
        detailedRated: Int = RANK_DETAILED_RATED,
    ): LocalRanking? = withContext(dispatchers.io) {
        val profile = profileState.value ?: rebuildFromCache()
        if (profile == null || !profile.isUsable) return@withContext null
        val rated = profile.ratedSubjectScores
        val pool = runCatchingCancellable {
            workFeatureRepository.getCachedPool(
                LOCAL_RANK_LIMIT,
            )
        }.getOrDefault(emptyList())
            .filter { it.hasSignal && (it.mediaType == null || it.mediaType == MediaType.ANIME) }
        val overrides = loadTagOverrides()
        val scored = parallelMap(pool) { f ->
            val match =
                computeMatch(f, profile, rated[f.subjectId], overrides, withNeighbors = false, withReasons = false)
            f to match.score
        }.sortedByDescending { it.second }
        val (ratedScored, unratedScored) = scored.partition { it.first.subjectId in rated }
        val top = unratedScored.take(detailedUnrated) + ratedScored.take(detailedRated)
        val entries = parallelMap(top) { (f, _) ->
            val mine = rated[f.subjectId]
            LocalRankedWork(f, computeMatch(f, profile, mine, overrides, withNeighbors = false), mine)
        }.sortedByDescending { it.match.score }
        LocalRanking(entries, poolSize = pool.size, unratedCount = unratedScored.size)
    }

    /** 分块并行映射（纯计算，结果顺序与输入一致）。 */
    private suspend fun <T, R> parallelMap(items: List<T>, transform: (T) -> R): List<R> = coroutineScope {
        items.chunked((items.size / PARALLEL_CHUNKS + 1).coerceAtLeast(MIN_CHUNK)).map { chunk ->
            async(dispatchers.default) { chunk.map(transform) }
        }.awaitAll().flatten()
    }

    private class BatchMemo(
        val profile: AdvancedTasteProfile,
        val overridesHash: Int,
        val results: java.util.concurrent.ConcurrentHashMap<String, TasteMatchResult> =
            java.util.concurrent.ConcurrentHashMap(),
    )

    @Volatile
    private var batchMemo: BatchMemo? = null

    /** 画像或标签覆盖表变了就换一张新表；条目过多时清空，避免长期占内存。 */
    private fun batchMemoFor(
        profile: AdvancedTasteProfile,
        overrides: Map<String, TasteCategory>,
    ): java.util.concurrent.ConcurrentHashMap<String, TasteMatchResult> {
        val hash = overrides.hashCode()
        val current = batchMemo
        val sameInputs = current != null && current.profile === profile && current.overridesHash == hash
        if (sameInputs && current!!.results.size < BATCH_MEMO_LIMIT) return current.results
        return BatchMemo(profile, hash).also { batchMemo = it }.results
    }

    suspend fun cachedFeatures(subjectIds: List<String>): Map<String, WorkFeature> = withContext(dispatchers.io) {
        if (subjectIds.isEmpty()) {
            emptyMap()
        } else {
            runCatchingCancellable {
                workFeatureRepository.getFeatures(
                    subjectIds.distinct(),
                    0,
                )
            }.getOrDefault(emptyMap())
        }
    }

    /**
     * 当前画像（和对照模型）对这些作品的预测，供预测账本记录：只用本地缓存的作品资料、不联网，
     * 已经评过分的作品跳过（账本只记事前预测）。画像不可用或没有预计评分模型时返回空表。
     */
    @Suppress("ReturnCount") // 几种「没法记」的情况各自提前返回空表，比层层嵌套好读
    suspend fun predictForLedger(subjectIds: Collection<String>): List<LedgerPrediction> {
        val profile = profileState.value?.takeIf { it.isUsable } ?: return emptyList()
        val main = profile.divergence ?: return emptyList()
        val ids = subjectIds.filter { it.isNotBlank() && it !in profile.ratedSubjectScores }.distinct()
        if (ids.isEmpty()) return emptyList()
        val features = cachedFeatures(ids)
        if (features.isEmpty()) return emptyList()
        val overrides = loadTagOverrides()
        val shadow = profile.shadowDivergence?.let { profile.copy(divergence = it) }
        return withContext(dispatchers.default) {
            features.values.flatMap { feature ->
                listOfNotNull(
                    LedgerPrediction(
                        feature.subjectId,
                        TasteModelVersion.ledger(profile, main) ?: main.modelVersion,
                        computeMatch(feature, profile, null, overrides, withNeighbors = false, withReasons = false),
                        profile.sampleCount,
                    ),
                    shadow?.let {
                        LedgerPrediction(
                            feature.subjectId,
                            TasteModelVersion.ledger(profile, it.divergence) ?: it.divergence!!.modelVersion,
                            computeMatch(feature, it, null, overrides, withNeighbors = false, withReasons = false),
                            profile.sampleCount,
                        )
                    },
                )
            }
        }
    }

    /**
     * 换了口味匹配度分支：不用重新建画像（几秒），把新分支套到现有画像上即可，各页面马上按新分支出分。
     * 还没有画像时什么都不做（建画像时会读到新分支）。
     */
    suspend fun reapplyMatchBranch() {
        rebuildMutex.withLock {
            profileState.value?.let { profileState.value = withMatchBranch(it) }
        }
    }

    /** 按设置里选的分支（常规 / 保守 / 激进）带上口味匹配度公式。 */
    private suspend fun withMatchBranch(profile: AdvancedTasteProfile): AdvancedTasteProfile {
        val key = runCatchingCancellable { settingsDataStore.tasteMatchBranch.first() }.getOrNull()
        val branch = TasteMatchBranch.fromKey(key)
        return profile.copy(matchBranch = branch, headlineFormula = algorithmConfig.current().formula(branch))
    }

    /**
     * 特征里缺社区评分时，用本地已存的 Bangumi 评分补上（详情页打开过的作品都有）。
     * 评分模型只用有社区评分的作品，缺了它，这部作品就进不了模型。
     */
    private suspend fun withCommunityFallback(features: Map<String, WorkFeature>): Map<String, WorkFeature> {
        val missing = features.filterValues { (it.bangumiScore ?: 0f) <= 0f }.keys
        val stored = if (missing.isEmpty()) {
            emptyMap()
        } else {
            // 只按缺评分的作品分批查，不把整张评分表读进内存。
            runCatchingCancellable {
                missing.chunked(SQL_IN_CHUNK).flatMap { ratingDao.getByWorks(it) }
            }.getOrDefault(emptyList())
                .filter { it.sourceId == SourceId.BANGUMI.name && !it.missing && it.score > 0f }
                .associateBy { it.workId }
        }
        return if (stored.isEmpty()) {
            features
        } else {
            features.mapValues { (id, f) ->
                stored[id]?.let { r ->
                    f.copy(bangumiScore = r.score, bangumiVotes = f.bangumiVotes.takeIf { it > 0 } ?: r.voteCount)
                } ?: f
            }
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
        return runCatchingCancellable { OffsetDateTime.parse(s).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatchingCancellable { Instant.parse(s).toEpochMilli() }.getOrNull()
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

        /** `IN (...)` 查询每批的 id 数，避开 SQLite 变量上限。 */
        const val SQL_IN_CHUNK = 900
        const val EXPLORE_CONTEXT = "explore"

        /** 详情页作品特征首次获取失败后的重试间隔。 */
        val FEATURE_RETRY_DELAYS_MS = longArrayOf(2_000L, 6_000L, 15_000L)

        /** 批量评分分成约这么多块并行（手机一般 4–8 核）。 */
        const val PARALLEL_CHUNKS = 4
        const val MIN_CHUNK = 32

        /** 本地排名一次最多算多少部（作品资料库里通常几百到几千部）。 */
        const val LOCAL_RANK_LIMIT = 20_000
        const val RANK_DETAILED = 500
        const val RANK_DETAILED_RATED = 300

        /** 批量评分记忆表的上限：超过就整表换新。 */
        const val BATCH_MEMO_LIMIT = 6_000

        /** 隐式信号回看窗口：半年前的滑动代表不了现在的兴趣。 */
        const val IMPLICIT_WINDOW_MS = 180L * 24 * 60 * 60 * 1000
    }
}

private fun AdvancedTasteProfile.withRatedScores(scores: Map<String, Int>): AdvancedTasteProfile =
    if (scores.isEmpty()) this else copy(ratedSubjectScores = scores)

private fun WorkFeature.needsExtendedRefresh(): Boolean =
    completeness != com.acgcompass.domain.taste.WorkFeatureCompleteness.EXTENDED

/** 账本用的一条预测：哪个模型版本、对哪部作品、算出了什么。 */
data class LedgerPrediction(
    val subjectId: String,
    val modelVersion: String,
    val match: TasteMatchResult,
    val profileSize: Int,
)
