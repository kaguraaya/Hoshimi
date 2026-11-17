package com.acgcompass.data.sync

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.BackgroundTaskStateDao
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.BackgroundTaskStateEntity
import com.acgcompass.data.taste.AiTagClassifier
import com.acgcompass.data.taste.TasteEngine
import com.acgcompass.data.taste.toTasteInputFingerprint
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.domain.taste.WorkFeatureRepository
import com.acgcompass.domain.usecase.TasteInputRecord
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 口味画像自动更新。观察本地个人收藏 [UserCollectionDao] 的任何变化——
 * Bangumi 同步入库、详情页「我的记录」改状态/评分、加入待补池置「想看」等——防抖后自动
 * 以最新收藏重算并持久化口味画像，无需用户手动点「从 Bangumi 导入」。
 *
 * 设计要点：
 * - **防抖**（[DEBOUNCE_MS]）：把一次批量同步（`upsertAll`）或连续编辑合并为一次重算，
 *   避免逐条触发造成抖动与浪费。
 * - **内容去重**：[distinctUntilChanged] 跳过内容未变的重复发射。
 * - **空样本跳过**：收藏为空时不重算（不以空样本生成无意义画像）。
 * - **best-effort**：单次重算失败被吞掉（绝不崩溃，下次变更再试）。
 * - 由 [com.acgcompass.app.AcgCompassApplication] 在启动时以 app 级 scope 调用 [start]，全程随应用存活。
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions")
class TasteProfileAutoUpdater @Inject constructor(
    private val userCollectionDao: UserCollectionDao,
    private val backlogDao: BacklogDao,
    private val tasteProfileRepository: TasteProfileRepository,
    private val settingsDataStore: SettingsDataStore,
    private val tasteEngine: TasteEngine,
    private val taskStateDao: BackgroundTaskStateDao,
    private val aiTagClassifier: AiTagClassifier,
    private val workFeatureRepository: WorkFeatureRepository,
    private val dispatchers: DispatcherProvider,
) {
    private val updateMutex = Mutex()

    @Volatile
    private var applicationScope: CoroutineScope? = null

    /**
     * 启动自动更新观察。在个人收藏变化、防抖稳定后，用最新收藏重算口味画像。
     * @param scope 应用级协程作用域（随应用存活，不随单个页面销毁）。
     */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        applicationScope = scope
        // 画像只由个人收藏（评分 / 状态 / 短评 / 标签）决定：指纹只看这些。
        // 待补池的增减不改变画像
        userCollectionDao.observeAll()
            .map { collections -> inputFingerprint(collections) to collections }
            .debounce(DEBOUNCE_MS)
            .distinctUntilChanged { old, new -> old.first == new.first }
            .onEach { (fingerprint, collections) ->
                recompute(fingerprint, collections, tasteFeatureIds(collections), force = false)
            }
            .flowOn(dispatchers.io)
            .launchIn(scope)
        // 换了口味匹配度分支：用缓存重建一次画像，分数马上按新分支算。
        settingsDataStore.tasteMatchBranch
            .distinctUntilChanged()
            .drop(1)
            .onEach { runCatching { tasteEngine.rebuildFromCache() } }
            .flowOn(dispatchers.io)
            .launchIn(scope)
        // 待补池新加的作品只需要把标签补回来（详情 / 推荐里好出分），安静地在后台做，不动画像。
        backlogDao.observeAll()
            .map { backlog -> backlog.map { it.workId }.filter { it.toIntOrNull() != null }.distinct().sorted() }
            .debounce(DEBOUNCE_MS)
            .distinctUntilChanged()
            .onEach { ids -> prefetchMissingFeatures(ids) }
            .flowOn(dispatchers.io)
            .launchIn(scope)
    }

    /** 后台补齐缺标签的作品，失败就算了（下次再试）；不写任务状态，界面上不会显示「正在分析」。 */
    private suspend fun prefetchMissingFeatures(ids: List<String>) {
        if (ids.isEmpty()) return
        runCatching {
            workFeatureRepository.ensureBasic(ids, networkBudget = PREFETCH_BUDGET, background = true)
        }
    }

    /** 校验个人输入指纹，仅在输入或特征稳定性发生变化时重建。 */
    suspend fun ensureCurrent(): Boolean {
        val collections = userCollectionDao.getAll()
        if (collections.isEmpty()) return false
        return recompute(inputFingerprint(collections), collections, tasteFeatureIds(collections), force = false)
    }

    /** 手动刷新与自动更新共用同一个互斥任务。 */
    suspend fun refreshNow(): Boolean {
        val scope = applicationScope
        return if (scope == null) {
            refreshNowInCurrentScope()
        } else {
            // 分析属于应用级任务；页面离开只取消等待，不中断标签收集和画像持久化。
            scope.async { refreshNowInCurrentScope() }.await()
        }
    }

    private suspend fun refreshNowInCurrentScope(): Boolean {
        val collections = userCollectionDao.getAll()
        if (collections.isEmpty()) return false
        return recompute(inputFingerprint(collections), collections, tasteFeatureIds(collections), force = true)
    }

    private suspend fun recompute(
        fingerprint: String,
        collections: List<com.acgcompass.data.local.entity.UserCollectionEntity>,
        priorityIds: List<String>,
        force: Boolean,
    ): Boolean = updateMutex.withLock {
        val previousTask = taskStateDao.get(TASK_KEY)
        val interruptedTask = previousTask?.phase == "COLLECTING" || previousTask?.phase == "BUILDING"
        val savedFingerprintMatches = settingsDataStore.tasteProfileInputFingerprint.first() == fingerprint
        // 输入没变就不重建；个别作品缺标签不触发重建（缺的在后台补）。
        if (!force && savedFingerprintMatches && !interruptedTask) {
            if (tasteEngine.currentProfile == null) runCatching { tasteEngine.rebuildFromCache() }
            prefetchMissingFeatures(priorityIds)
            return@withLock false
        }
        // 自动触发的重建只读本地缓存，几秒内完成；缺资料的作品之后在后台分批补（restoreInBackground）。
        // 用户手动点的「重新分析」才联网补齐，并用前台优先级抢时间。
        val basicCollection = collectBasicFeatures(
            fingerprint,
            priorityIds,
            background = !force,
            networkBudget = if (force) priorityIds.size else 0,
        )
        val basicFeatures = basicCollection.features
        // 个别作品取不到标签时不让整次分析失败：用取到的部分建画像，缺的以后在后台补。
        if (basicFeatures.isEmpty() && priorityIds.isNotEmpty()) {
            persistFailure(fingerprint, priorityIds.size, "个人作品的基础标签暂时取不到，稍后会自动重试")
            return@withLock false
        }
        persistBuildingState(basicCollection.state, priorityIds.size)
        val records = buildTasteRecords(collections, basicFeatures)
        // 基础画像只使用已落库的评分、收藏状态、短评和经过校验的作品标签；
        // Staff、角色和声优等扩展特征缺失时不影响本次基础画像完成。
        val result = runCatching { tasteProfileRepository.importAndCompute(records) }.getOrNull()
        if (result !is AppResult.Success) {
            persistFailure(fingerprint, priorityIds.size)
            return@withLock false
        }
        val planPrepared = runCatching { aiTagClassifier.prepareStablePlan() }.isSuccess
        if (!planPrepared) {
            persistFailure(fingerprint, priorityIds.size, "本地标签候选整理失败")
            return@withLock false
        }
        settingsDataStore.setTasteProfileInputFingerprint(fingerprint)
        // 不可变 AI 候选快照建立后再发布高级画像，使完成状态和待分类数量属于同一代输入。
        runCatching { tasteEngine.rebuildFromCache() }
        val completedAt = System.currentTimeMillis()
        persistCompletedState(fingerprint, priorityIds.size, completedAt)
        restoreInBackground(priorityIds)
        true
    }

    @Volatile
    private var restoreJob: Job? = null

    /**
     * 后台分批补齐还没有完整标签的作品（每批 [PREFETCH_BUDGET] 部，走后台优先级），每补完一批就用缓存静默重建一次画像。
     * 一批没有任何进展（例如条目需要登录才能看）就停下，下次画像更新时再试。
     */
    private fun restoreInBackground(ids: List<String>) {
        val scope = applicationScope ?: return
        if (ids.isEmpty() || restoreJob?.isActive == true) return
        restoreJob = scope.launch(dispatchers.io) {
            repeat(MAX_RESTORE_ROUNDS) {
                val before = runCatching { workFeatureRepository.countUnverified(ids) }.getOrDefault(0)
                if (before == 0) return@launch
                // 每批打乱顺序：个别一直取不到的作品不会每次都占住名额。
                runCatching {
                    workFeatureRepository.ensureBasic(
                        ids.shuffled(),
                        networkBudget = PREFETCH_BUDGET,
                        background = true,
                    )
                }
                val after = runCatching { workFeatureRepository.countUnverified(ids) }.getOrDefault(before)
                if (after >= before) return@launch
                runCatching { tasteEngine.rebuildFromCache() }
            }
        }
    }

    private suspend fun collectBasicFeatures(
        fingerprint: String,
        priorityIds: List<String>,
        background: Boolean,
        networkBudget: Int,
    ): BasicFeatureCollection {
        val now = System.currentTimeMillis()
        val previous = taskStateDao.get(TASK_KEY)
        val state = newTaskState(fingerprint, priorityIds.size, now).copy(completedAt = previous?.completedAt)
        taskStateDao.upsert(state)
        val progressMutex = Mutex()
        var lastPersistedProgress = 0
        val progressStep = max(1, priorityIds.size / MAX_PROGRESS_UPDATES)
        val features = try {
            workFeatureRepository.ensureBasic(
                priorityIds,
                networkBudget = networkBudget,
                background = background,
            ) { done, total ->
                progressMutex.withLock {
                    if (done < total && done - lastPersistedProgress < progressStep) return@withLock
                    if (done <= lastPersistedProgress) return@withLock
                    lastPersistedProgress = done
                    taskStateDao.upsert(
                        state.copy(
                            completed = done,
                            total = total,
                            remaining = (total - done).coerceAtLeast(0),
                            updatedAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyMap()
        }
        return BasicFeatureCollection(features, state)
    }

    private suspend fun persistBuildingState(state: BackgroundTaskStateEntity, total: Int) {
        taskStateDao.upsert(
            state.copy(
                phase = "BUILDING",
                completed = total,
                remaining = 0,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun buildTasteRecords(
        collections: List<com.acgcompass.data.local.entity.UserCollectionEntity>,
        features: Map<String, WorkFeature>,
    ): List<TasteInputRecord> = collections.map { collection ->
        val featureId = collection.sourceItemId.ifBlank { collection.localWorkId }
        TasteInputRecord(
            rating = collection.rating,
            tags = mergeTasteInputTags(collection.tags, features[featureId]),
            reviewText = collection.comment,
            status = collection.status,
            updatedAt = collection.updatedAt,
        )
    }

    private companion object {
        /** 防抖窗口：合并批量同步 / 连续编辑为一次重算。 */
        const val DEBOUNCE_MS = 1_500L
        const val TASK_KEY = "local_taste_analysis"
        const val RETRY_DELAY_MS = 15L * 60L * 1_000L

        /** 一次后台补标签最多发多少个请求：够补新加的几部，又不会长时间占着限流额度。 */
        const val PREFETCH_BUDGET = 40
        const val MAX_PROGRESS_UPDATES = 100

        /** 后台补齐最多跑这么多批（每批 [PREFETCH_BUDGET] 部）。 */
        const val MAX_RESTORE_ROUNDS = 25
    }

    private suspend fun persistFailure(fingerprint: String, total: Int, message: String = "本地画像计算失败") {
        val now = System.currentTimeMillis()
        val old = taskStateDao.get(TASK_KEY)
        taskStateDao.upsert(
            (old ?: newTaskState(fingerprint, total, now)).copy(
                phase = "IDLE",
                updatedAt = now,
                failedCount = 1,
                error = message,
                retryAt = now + RETRY_DELAY_MS,
                lastResult = "FAILED",
            ),
        )
    }

    private suspend fun persistCompletedState(fingerprint: String, total: Int, completedAt: Long) {
        val old = taskStateDao.get(TASK_KEY)
        taskStateDao.upsert(
            (old ?: newTaskState(fingerprint, total, completedAt)).copy(
                phase = "IDLE",
                completed = total,
                remaining = 0,
                completedAt = completedAt,
                updatedAt = completedAt,
                error = null,
                retryAt = null,
                lastResult = "COMPLETED",
            ),
        )
    }

    private fun newTaskState(fingerprint: String, total: Int, now: Long) = BackgroundTaskStateEntity(
        taskKey = TASK_KEY,
        inputFingerprint = fingerprint,
        phase = "COLLECTING",
        completed = 0,
        total = total,
        batchIndex = 1,
        batchCount = 1,
        automaticCount = 0,
        reviewCount = 0,
        rejectedCount = 0,
        failedCount = 0,
        remaining = total,
        startedAt = now,
        completedAt = null,
        updatedAt = now,
        error = null,
        retryAt = null,
        lastResult = null,
        candidateFingerprint = fingerprint,
        personalInputFingerprint = fingerprint,
    )

    private data class BasicFeatureCollection(
        val features: Map<String, WorkFeature>,
        val state: BackgroundTaskStateEntity,
    )

    /** 参与画像的作品 id（只取能映射到 Bangumi 条目的）。 */
    private fun tasteFeatureIds(
        collections: List<com.acgcompass.data.local.entity.UserCollectionEntity>,
    ): List<String> = collections.asSequence()
        .map { it.sourceItemId.ifBlank { it.localWorkId } }
        .filter { it.toIntOrNull() != null }
        .distinct()
        .sorted()
        .toList()

    private fun inputFingerprint(collections: List<com.acgcompass.data.local.entity.UserCollectionEntity>): String =
        collections.toTasteInputFingerprint()
}

internal fun mergeTasteInputTags(personalTags: List<String>, feature: WorkFeature?): List<String> =
    (personalTags.asSequence() + feature.orEmptyTagNames())
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .toList()

private fun WorkFeature?.orEmptyTagNames(): Sequence<String> =
    this?.tagCounts?.asSequence()?.map { it.name } ?: emptySequence()
