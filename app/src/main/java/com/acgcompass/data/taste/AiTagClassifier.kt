package com.acgcompass.data.taste

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.BackgroundTaskStateDao
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.TagClassificationPlanDao
import com.acgcompass.data.local.dao.TagDimensionDao
import com.acgcompass.data.local.dao.TasteTagProcessingDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.BackgroundTaskStateEntity
import com.acgcompass.data.local.entity.TagClassificationPlanEntity
import com.acgcompass.data.local.entity.TagDimensionEntity
import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.AiRunOptions
import com.acgcompass.domain.ai.AiRunResult
import com.acgcompass.domain.ai.AiTask
import com.acgcompass.domain.ai.TagClassifyOutput
import com.acgcompass.domain.taste.ManualTagReview
import com.acgcompass.domain.taste.TagClassificationPhase
import com.acgcompass.domain.taste.TagClassifier
import com.acgcompass.domain.taste.TagClassifyProgress
import com.acgcompass.domain.taste.TagDimensionBucket
import com.acgcompass.domain.taste.TagDimensionSummary
import com.acgcompass.domain.taste.TasteCategory
import com.acgcompass.domain.taste.TasteProcessingSummary
import com.acgcompass.domain.taste.WorkFeatureCompleteness
import com.acgcompass.domain.taste.WorkFeatureRepository
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 后台标签升维分类器：将本地规则暂归为题材的未知社区标签分批交由 AI 分类，
 * 结果落库到 `tag_dimensions`（[TagDimensionDao]），供 [TasteEngine] 在画像构建 / 评分时读为覆盖表。
 *
 * 设计要点：
 * - **只分类真正未知的标签**：候选取自 [WorkFeatureRepository.getCachedPool] 全体社区标签，经
 *   [TagClassifier.isUnknownTopicFallback] 过滤（已被词典 / 交叉验证命中的标签本地已能精确分类，不送 AI），
 *   按出现频次降序优先分类高频标签（更影响画像 / 评分），并排除已缓存标签避免重复调用。
 * - **回退优先**：AI 未配置 → 立即停止并回退本地（画像照常工作）；单批低置信 / 失败 → 跳过该批继续，
 *   绝不写入伪造维度。非法维度 key 经 [TasteCategory.fromKey] 过滤丢弃。
 * - **手动触发、单例串行**：由画像页显式触发（成本可控）；[mutex] 保证同一时刻只运行一个计划。
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions")
class AiTagClassifier @Inject constructor(
    private val aiEngine: AiEngine,
    private val workFeatureRepository: WorkFeatureRepository,
    private val tagDimensionDao: TagDimensionDao,
    private val planDao: TagClassificationPlanDao,
    private val taskStateDao: BackgroundTaskStateDao,
    private val userCollectionDao: UserCollectionDao,
    private val backlogDao: BacklogDao,
    private val processingDao: TasteTagProcessingDao,
    private val dispatchers: DispatcherProvider,
    private val settingsDataStore: SettingsDataStore,
) {

    private val progressState = MutableStateFlow<TagClassifyProgress?>(null)

    /** 分类进度流（`null` = 无分类进行中）。设置页可观察以展示进度条。 */
    fun observeProgress(): StateFlow<TagClassifyProgress?> = progressState.asStateFlow()

    private val mutex = Mutex()

    /**
     * 分类一批待升维的未知标签并写入缓存。串行执行；[maxTags] 限定单轮送 AI 的标签上限（成本可控，
     * 可多次触发逐步补全）。返回 [TagClassifyOutcome] 供 UI 展示结果。
     */
    suspend fun classifyPending(maxTags: Int = 0): TagClassifyOutcome = mutex.withLock {
        try {
            withContext(dispatchers.io) {
                val configuredMax = if (maxTags > 0) maxTags else settingsDataStore.aiTagClassificationMax.first()
                val plan = loadOrCreatePlan(configuredMax.coerceIn(MIN_CONFIGURED_TAGS, MAX_CONFIGURED_TAGS))
                if (plan.isEmpty()) TagClassifyOutcome.NothingToDo else classifyPlan(plan)
            }
        } finally {
            progressState.value = null
        }
    }

    private suspend fun classifyPlan(plan: List<TagClassificationPlanEntity>): TagClassifyOutcome {
        val planId = plan.first().planId
        val batches = plan.chunked(TAG_CLASSIFICATION_BATCH_SIZE)
        var anyAiResponded = false
        for ((index, batch) in batches.withIndex()) {
            updateProgress(index, batches.size, plan.size, TagClassificationPhase.REQUESTING)
            persistRunState(planId, phase = TagClassificationPhase.REQUESTING, active = true)
            val result = classifyBatch(batch)
            if (result.notConfigured) {
                persistRunState(planId, phase = null, active = false, result = "NOT_CONFIGURED")
                return TagClassifyOutcome.NotConfigured
            }
            anyAiResponded = anyAiResponded || result.responded
            updateProgress(index + 1, batches.size, plan.size, TagClassificationPhase.WRITING)
            persistRunState(planId, phase = TagClassificationPhase.WRITING, active = true)
        }
        val counts = planCounts(planId)
        return if (!anyAiResponded) {
            persistRunState(planId, phase = null, active = false, result = "FAILED", error = "AI 服务未返回可用结果")
            TagClassifyOutcome.AiUnresponsive
        } else {
            val result = if (counts.failed > 0) "PARTIAL" else "COMPLETED"
            val error = counts.failed.takeIf { it > 0 }?.let { "$it 个标签请求失败，已保留以便重试" }
            persistRunState(planId, phase = null, active = false, result = result, error = error)
            TagClassifyOutcome.Done(
                classified = counts.automatic,
                requested = counts.total,
                review = counts.review,
                rejected = counts.rejected,
                failed = counts.failed,
            )
        }
    }

    private suspend fun classifyBatch(batch: List<TagClassificationPlanEntity>): BatchClassificationResult {
        val tags = batch.map { it.rawTag }
        val task = AiTask.TagClassify(
            content = buildTagClassificationContent(tags),
            tags = tags,
            dataSources = listOf("work_features 社区标签"),
        )
        return when (val result = aiEngine.run(task, AiRunOptions(confirmed = true))) {
            is AiRunResult.Success -> {
                progressState.value = progressState.value?.copy(phase = TagClassificationPhase.VALIDATING)
                val mapped = mapBatchResult(result.payload, batch)
                if (mapped.dimensions.isNotEmpty() || mapped.planEntries.isNotEmpty()) {
                    progressState.value = progressState.value?.copy(phase = TagClassificationPhase.WRITING)
                    if (mapped.dimensions.isNotEmpty()) tagDimensionDao.upsertAll(mapped.dimensions)
                    planDao.upsertAll(mapped.planEntries)
                    processingDao.markAiTerminal(
                        tags = mapped.planEntries
                            .filter { it.status in TERMINAL_STATUSES }
                            .mapTo(HashSet()) { it.tag },
                        planId = batch.first().planId,
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                BatchClassificationResult(responded = true)
            }
            is AiRunResult.NotConfigured -> BatchClassificationResult(notConfigured = true)
            else -> {
                val now = System.currentTimeMillis()
                planDao.upsertAll(batch.map { it.copy(status = STATUS_FAILED, error = "请求失败", updatedAt = now) })
                BatchClassificationResult()
            }
        }
    }

    private fun updateProgress(doneBatches: Int, batchCount: Int, total: Int, phase: TagClassificationPhase) {
        progressState.value = TagClassifyProgress(
            done = minOf(doneBatches * TAG_CLASSIFICATION_BATCH_SIZE, total),
            total = total,
            batchIndex = (doneBatches + 1).coerceAtMost(batchCount),
            batchCount = batchCount,
            phase = phase,
        )
    }

    /** AI 已缓存的标签数（UI 展示 / 判断是否需要分类）。读失败返回 0。 */
    suspend fun cachedCount(): Int = withContext(dispatchers.io) {
        runCatching { tagDimensionDao.count() }.getOrDefault(0)
    }

    suspend fun pendingCount(): Int = withContext(dispatchers.io) {
        val state = taskStateDao.get(TASK_KEY)
        val planRows = state?.inputFingerprint
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { planDao.getByPlanId(it) }.getOrDefault(emptyList()) }
            .orEmpty()
        // 页面只观察既有快照；候选计划只在本地画像成功后建立，后台补齐不能改变当前显示数量。
        val currentPlanRemaining = planRows.count { it.status in setOf(STATUS_PENDING, STATUS_FAILED) }
        if (currentPlanRemaining > 0) return@withContext currentPlanRemaining

        // 单轮达到设置上限后仍有稳定候选时立即显示下一批数量，最后一个标签也不能被隐藏。
        if (state?.lastResult == null) return@withContext 0
        val personalIds = personalFeatureIds()
        if (personalIds.isEmpty() || !hasCompleteBasicFeatures(personalIds)) return@withContext 0
        if (state.personalInputFingerprint != personalInputFingerprint(personalIds)) return@withContext 0
        val configuredMax = settingsDataStore.aiTagClassificationMax.first()
            .coerceIn(MIN_CONFIGURED_TAGS, MAX_CONFIGURED_TAGS)
        pendingCandidates(configuredMax, personalIds).size
    }

    /** 本地画像成功后建立一份不可变候选快照。 */
    suspend fun prepareStablePlan(): Int = withContext(dispatchers.io) {
        mutex.withLock { prepareStablePlanLocked() }
    }

    @Suppress("ReturnCount")
    private suspend fun prepareStablePlanLocked(): Int {
        val state = taskStateDao.get(TASK_KEY)
        if (state != null && state.lastResult == null && state.phase in ACTIVE_PHASES) {
            return state.remaining
        }
        val personalIds = personalFeatureIds()
        if (personalIds.isEmpty() || !hasCompleteBasicFeatures(personalIds)) {
            // 特征不完整时不发布候选，避免数量随后台补齐顺序持续变化。
            return 0
        }
        val personalFingerprint = personalInputFingerprint(personalIds)
        val localTags = collectLocalTags(personalIds)
        processingDao.replaceLocalSnapshot(localTags, System.currentTimeMillis())
        val configuredMax = settingsDataStore.aiTagClassificationMax.first()
            .coerceIn(MIN_CONFIGURED_TAGS, MAX_CONFIGURED_TAGS)
        val candidates = pendingCandidates(configuredMax, personalIds)
        val fingerprint = candidates.joinToString("\n") { TagClassifier.clean(it) }.sha256()
        reusePendingCount(state, personalFingerprint, fingerprint)?.let { return it }
        if (candidates.isEmpty()) {
            val now = System.currentTimeMillis()
            taskStateDao.upsert(
                newReadyState(
                    planId = "empty-${personalFingerprint.take(EMPTY_PLAN_FINGERPRINT_PREFIX_LENGTH)}",
                    total = 0,
                    now = now,
                    candidateFingerprint = fingerprint,
                    personalInputFingerprint = personalFingerprint,
                ),
            )
            return 0
        }
        val existing = state?.inputFingerprint
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { planDao.getByPlanId(it) }.getOrDefault(emptyList()) }
            .orEmpty()
        if (existing.isNotEmpty() && existing.first().candidateFingerprint == fingerprint) {
            return existing.count { it.status in setOf(STATUS_PENDING, STATUS_FAILED) }
        }
        val planId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val plan = candidates.mapIndexedNotNull { index, rawTag ->
            val tag = TagClassifier.clean(rawTag).takeIf { it.isNotBlank() }
                ?: return@mapIndexedNotNull null
            TagClassificationPlanEntity(
                tag = tag,
                rawTag = rawTag,
                planId = planId,
                position = index,
                status = STATUS_PENDING,
                dimension = null,
                confidence = null,
                error = null,
                classifierVersion = CLASSIFIER_VERSION,
                candidateFingerprint = fingerprint,
                updatedAt = now,
            )
        }
        planDao.upsertAll(plan)
        taskStateDao.upsert(newReadyState(planId, plan.size, now, fingerprint, personalFingerprint))
        return plan.size
    }

    private suspend fun reusePendingCount(
        state: BackgroundTaskStateEntity?,
        personalFingerprint: String,
        candidateFingerprint: String,
    ): Int? {
        if (
            state?.personalInputFingerprint != personalFingerprint ||
            state.candidateFingerprint != candidateFingerprint
        ) {
            return null
        }
        val currentRows = state.inputFingerprint
            .takeIf(String::isNotBlank)
            ?.let { runCatching { planDao.getByPlanId(it) }.getOrDefault(emptyList()) }
            .orEmpty()
        return currentRows.count { it.status in setOf(STATUS_PENDING, STATUS_FAILED) }
    }

    suspend fun processingSummary(): TasteProcessingSummary = withContext(dispatchers.io) {
        val ids = personalFeatureIds()
        val covered = workFeatureRepository.getFeatures(ids.toList(), networkBudget = 0).values.count {
            it.completeness != WorkFeatureCompleteness.EMBEDDED
        }
        TasteProcessingSummary(
            localUniqueTags = processingDao.countLocalSeen(),
            aiTerminalUniqueTags = processingDao.countAiTerminal(),
            cumulativeUniqueTags = processingDao.countCumulative(),
            coveredPriorityWorks = covered,
            totalPriorityWorks = ids.size,
        )
    }

    /**
     * AI「升维」效果摘要——本地规则会把未知社区标签统统兜底成笼统的「题材」；这里统计 AI 已把多少
     * 这类标签细化到了哪些**更精确**的维度（情节 / 角色类型 / 来源 / 年代 / 社区梗…），按标签数降序。
     * 供画像页展示分类的实际收益（而非只有一个「已分类 N 个」的 Toast）。读失败返回空摘要（UI 隐藏该区）。
     */
    suspend fun dimensionSummary(): TagDimensionSummary = withContext(dispatchers.io) {
        val counts = runCatching { tagDimensionDao.getDimensionCounts() }.getOrDefault(emptyList())
        val byCategory = counts
            .mapNotNull { row -> TasteCategory.fromKey(row.dimension)?.let { it to row.count } }
            .groupBy({ it.first }, { it.second })
            .map { (cat, list) -> TagDimensionBucket(category = cat, count = list.sum()) }
            .sortedByDescending { it.count }
        TagDimensionSummary(
            total = byCategory.sumOf { it.count },
            buckets = byCategory,
        )
    }

    /** 把 AI 输出映射为缓存实体；只接受本批请求集合内的标签，并兼容维度别名。 */
    private fun mapBatchResult(
        output: TagClassifyOutput,
        batch: List<TagClassificationPlanEntity>,
    ): MappedBatchResult {
        val assignments = resolveTagDimensionAssignments(output, batch.map { it.rawTag }).toMap()
        val now = System.currentTimeMillis()
        val confidence = output.confidence.coerceIn(0f, 1f)
        val dimensions = mutableListOf<TagDimensionEntity>()
        val planEntries = batch.map { entry ->
            val category = assignments[entry.tag]
            val status = tagClassificationDisposition(category, confidence).name
            if (status == STATUS_AUTOMATIC || status == STATUS_REJECTED) {
                val storedCategory = if (status == STATUS_REJECTED) TasteCategory.NOISE else requireNotNull(category)
                dimensions += TagDimensionEntity(
                    tag = entry.tag,
                    dimension = storedCategory.key,
                    source = "AI",
                    confidence = confidence,
                    updatedAt = now,
                    classifierVersion = CLASSIFIER_VERSION,
                    valid = true,
                )
            }
            entry.copy(
                status = status,
                dimension = category?.key,
                confidence = confidence,
                error = null,
                updatedAt = now,
            )
        }
        return MappedBatchResult(dimensions, planEntries)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "ReturnCount")
    private suspend fun loadOrCreatePlan(limit: Int): List<TagClassificationPlanEntity> {
        val state = taskStateDao.get(TASK_KEY)
        val personalIds = personalFeatureIds()
        val personalFingerprint = personalInputFingerprint(personalIds)
        if (
            state != null &&
            state.inputFingerprint.isNotBlank() &&
            state.personalInputFingerprint == personalFingerprint
        ) {
            val existing = planDao.getByPlanId(state.inputFingerprint)
            if (existing.isNotEmpty()) {
                val runnable = existing.filter { it.status == STATUS_PENDING || it.status == STATUS_FAILED }
                if (runnable.isNotEmpty() && state.lastResult == null) return runnable
                if (runnable.isNotEmpty() && state.lastResult in setOf("STOPPED", "FAILED", "PARTIAL")) {
                    val now = System.currentTimeMillis()
                    val reset = runnable.map { it.copy(status = STATUS_PENDING, error = null, updatedAt = now) }
                    planDao.upsertAll(reset)
                    return reset
                }
            }
            if (state.phase == "READY") return emptyList()
        }
        if (
            state != null &&
            state.personalInputFingerprint == personalFingerprint &&
            state.lastResult in setOf("STOPPED", "FAILED", "PARTIAL") &&
            state.inputFingerprint.isNotBlank()
        ) {
            val resumable = planDao.getAll().filter {
                it.planId == state.inputFingerprint && it.status in setOf(STATUS_PENDING, STATUS_FAILED)
            }
            if (resumable.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val reset = resumable.map { it.copy(status = STATUS_PENDING, error = null, updatedAt = now) }
                planDao.upsertAll(reset)
                return reset
            }
        }
        if (personalIds.isEmpty() || !hasCompleteBasicFeatures(personalIds)) return emptyList()
        val candidates = pendingCandidates(limit, personalIds)
        return if (candidates.isEmpty()) {
            emptyList()
        } else {
            val planId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val candidateFingerprint = candidates
                .joinToString(separator = "\n") { TagClassifier.clean(it) }
                .sha256()
            val plan = candidates.mapIndexedNotNull { index, rawTag ->
                val tag = TagClassifier.clean(rawTag).takeIf { it.isNotBlank() }
                    ?: return@mapIndexedNotNull null
                TagClassificationPlanEntity(
                    tag = tag,
                    rawTag = rawTag,
                    planId = planId,
                    position = index,
                    status = STATUS_PENDING,
                    dimension = null,
                    confidence = null,
                    error = null,
                    classifierVersion = CLASSIFIER_VERSION,
                    candidateFingerprint = candidateFingerprint,
                    updatedAt = now,
                )
            }
            planDao.upsertAll(plan)
            taskStateDao.upsert(newRunState(planId, plan.size, now, candidateFingerprint, personalFingerprint))
            plan
        }
    }

    private suspend fun planCounts(planId: String): PlanCounts {
        val rows = planDao.getAll().filter { it.planId == planId }
        return PlanCounts(
            total = rows.size,
            automatic = rows.count { it.status == STATUS_AUTOMATIC },
            review = rows.count { it.status == STATUS_REVIEW },
            rejected = rows.count { it.status == STATUS_REJECTED },
            manual = rows.count { it.status == STATUS_MANUAL },
            failed = rows.count { it.status == STATUS_FAILED },
            pending = rows.count { it.status == STATUS_PENDING },
        )
    }

    private suspend fun persistRunState(
        planId: String,
        phase: TagClassificationPhase?,
        active: Boolean,
        result: String? = null,
        error: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val old = taskStateDao.get(TASK_KEY)
        val counts = planCounts(planId)
        taskStateDao.upsert(
            (old ?: newRunState(planId, counts.total, now)).copy(
                inputFingerprint = planId,
                phase = phase?.name ?: if (active) "REQUESTING" else "IDLE",
                completed = counts.automatic + counts.review + counts.rejected + counts.manual,
                total = counts.total,
                batchIndex = progressState.value?.batchIndex ?: old?.batchIndex ?: 0,
                batchCount = progressState.value?.batchCount ?: old?.batchCount ?: 0,
                automaticCount = counts.automatic,
                reviewCount = counts.review,
                rejectedCount = counts.rejected,
                failedCount = counts.failed,
                remaining = counts.failed + counts.pending,
                completedAt = if (!active) now else null,
                updatedAt = now,
                error = error,
                retryAt = if (result in setOf("FAILED", "PARTIAL")) now + RETRY_DELAY_MILLIS else null,
                lastResult = result,
                notificationAcknowledged = result == null,
                candidateFingerprint = old?.candidateFingerprint.orEmpty(),
            ),
        )
    }

    suspend fun persistedState(): BackgroundTaskStateEntity? = withContext(dispatchers.io) {
        taskStateDao.get(TASK_KEY)
    }

    /** Marks a terminal result as seen without discarding the plan counters shown on the profile page. */
    suspend fun acknowledgeLastResult() = withContext(dispatchers.io) {
        val old = taskStateDao.get(TASK_KEY) ?: return@withContext
        if (old.lastResult != null && !old.notificationAcknowledged) {
            taskStateDao.upsert(
                old.copy(
                    notificationAcknowledged = true,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun markStopped() = withContext(dispatchers.io) {
        val old = taskStateDao.get(TASK_KEY) ?: return@withContext
        taskStateDao.upsert(
            old.copy(
                phase = "IDLE",
                updatedAt = System.currentTimeMillis(),
                lastResult = "STOPPED",
            ),
        )
    }

    suspend fun manualReviewItems(): List<ManualTagReview> = withContext(dispatchers.io) {
        planDao.getByStatus(STATUS_REVIEW).map { entry ->
            ManualTagReview(
                tag = entry.tag,
                proposedCategory = TasteCategory.fromKey(entry.dimension),
                confidence = entry.confidence,
            )
        }
    }

    suspend fun resolveManual(tags: Set<String>, category: TasteCategory) = withContext(dispatchers.io) {
        if (tags.isEmpty() || category !in TasteCategory.SINGLE_TAG_CATEGORIES) return@withContext
        val now = System.currentTimeMillis()
        val selected = planDao.getByStatus(STATUS_REVIEW).filter { it.tag in tags }
        if (selected.isEmpty()) return@withContext
        tagDimensionDao.upsertAll(
            selected.map { entry ->
                TagDimensionEntity(
                    tag = entry.tag,
                    dimension = category.key,
                    source = "MANUAL",
                    confidence = 1f,
                    updatedAt = now,
                    classifierVersion = CLASSIFIER_VERSION,
                    valid = true,
                )
            },
        )
        planDao.upsertAll(
            selected.map {
                it.copy(
                    status = STATUS_MANUAL,
                    dimension = category.key,
                    confidence = 1f,
                    updatedAt = now,
                )
            },
        )
        processingDao.markAiTerminal(
            tags = selected.mapTo(HashSet()) { it.tag },
            planId = selected.first().planId,
            updatedAt = now,
        )
        taskStateDao.get(TASK_KEY)?.let { state ->
            persistRunState(
                planId = state.inputFingerprint,
                phase = null,
                active = false,
                result = state.lastResult,
                error = state.error,
            )
        }
    }

    private suspend fun pendingCandidates(limit: Int, personalIds: Set<String>): List<String> {
        runCatching { tagDimensionDao.invalidateOlderVersions(CLASSIFIER_VERSION) }
        val cached = runCatching { tagDimensionDao.getCachedTags(CLASSIFIER_VERSION) }
            .getOrDefault(emptyList())
            .toHashSet()
        val terminalPlanTags = runCatching { planDao.getAll() }.getOrDefault(emptyList())
            .filter { it.status in setOf(STATUS_AUTOMATIC, STATUS_REVIEW, STATUS_REJECTED, STATUS_MANUAL) }
            .mapTo(HashSet()) { it.tag }
        if (personalIds.isEmpty()) return emptyList()
        // 按个人作品 id 读取完整特征集，避免公共缓存增长后截断个人标签并产生漂移。
        val pool = runCatching {
            workFeatureRepository.getFeatures(personalIds.toList(), networkBudget = 0).values.toList()
        }.getOrDefault(emptyList())

        val frequency = HashMap<String, Int>()
        val raw = HashMap<String, String>()
        val blocked = HashSet<String>()
        pool.forEach { feature ->
            val context = TagClassifier.Context.of(
                staff = feature.staff,
                characters = feature.characters,
                cv = feature.cv,
                sources = feature.sources,
                titles = feature.titleVariants,
            )
            feature.tagCounts.forEach { tagCount ->
                val cleaned = TagClassifier.clean(tagCount.name)
                if (cleaned.isEmpty()) return@forEach
                if (!TagClassifier.isUnknownTopicFallback(tagCount.name, context)) {
                    blocked += cleaned
                    return@forEach
                }
                frequency[cleaned] = (frequency[cleaned] ?: 0) + tagCount.count.coerceAtLeast(1)
                raw.putIfAbsent(cleaned, tagCount.name)
            }
        }
        blocked.forEach { frequency.remove(it) }

        // 先汇总完整个人标签池并排除终态，再应用计划上限，使按钮数量与不可变计划使用同一快照。
        return selectPendingCandidateTags(
            frequency = frequency,
            rawTags = raw,
            excluded = cached + terminalPlanTags,
            limit = limit.coerceAtMost(MAX_CONFIGURED_TAGS),
        )
    }

    private suspend fun personalFeatureIds(): Set<String> = buildSet {
        runCatching { userCollectionDao.getAll() }.getOrDefault(emptyList()).forEach {
            add(it.sourceItemId.ifBlank { it.localWorkId })
        }
        runCatching { backlogDao.getAll() }.getOrDefault(emptyList()).forEach { add(it.workId) }
    }.filter { it.toIntOrNull() != null }.toSet()

    private suspend fun personalInputFingerprint(personalIds: Set<String>): String = buildString {
        append(runCatching { userCollectionDao.getAll().toTasteInputFingerprint() }.getOrDefault(""))
        append('\n')
        personalIds.sorted().forEach { append("priority:").append(it).append('\n') }
    }.sha256()

    private suspend fun collectLocalTags(personalIds: Set<String>): Set<String> {
        val features = workFeatureRepository.getFeatures(personalIds.toList(), networkBudget = 0)
        return buildSet {
            features.values.forEach { feature ->
                val context = TagClassifier.Context.of(
                    staff = feature.staff,
                    characters = feature.characters,
                    cv = feature.cv,
                    sources = feature.sources,
                    titles = feature.titleVariants,
                )
                feature.tagCounts.forEach { tagCount ->
                    val cleaned = TagClassifier.clean(tagCount.name)
                    if (cleaned.isNotEmpty() && TagClassifier.classify(tagCount.name, context) != TasteCategory.NOISE) {
                        add(cleaned)
                    }
                }
            }
        }
    }

    private suspend fun hasCompleteBasicFeatures(ids: Set<String>): Boolean {
        return workFeatureRepository.hasStableBasicFeatures(ids)
    }

    private companion object {
        /** 单批送 AI 的标签数（一批约需千级输出 token，适配 2000 上限，避免 JSON 截断）。 */
        const val MIN_CONFIGURED_TAGS: Int = 100
        const val MAX_CONFIGURED_TAGS: Int = 1_000
        const val CLASSIFIER_VERSION: Int = 3
        const val EMPTY_PLAN_FINGERPRINT_PREFIX_LENGTH: Int = 16
        const val TASK_KEY: String = "ai_tag_classification"
        const val STATUS_PENDING: String = "PENDING"
        const val STATUS_AUTOMATIC: String = "AUTOMATIC"
        const val STATUS_REVIEW: String = "REVIEW"
        const val STATUS_REJECTED: String = "REJECTED"
        const val STATUS_FAILED: String = "FAILED"
        const val STATUS_MANUAL: String = "MANUAL"
        const val RETRY_DELAY_MILLIS: Long = 5L * 60L * 1_000L
        val ACTIVE_PHASES = setOf("REQUESTING", "VALIDATING", "WRITING")
        val TERMINAL_STATUSES = setOf(STATUS_AUTOMATIC, STATUS_REVIEW, STATUS_REJECTED, STATUS_MANUAL)
    }
}

internal fun selectPendingCandidateTags(
    frequency: Map<String, Int>,
    rawTags: Map<String, String>,
    excluded: Set<String>,
    limit: Int,
): List<String> = frequency.entries
    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    .filterNot { it.key in excluded }
    .take(limit.coerceAtLeast(0))
    .mapNotNull { rawTags[it.key] }

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal enum class TagClassificationDisposition {
    AUTOMATIC,
    REVIEW,
    REJECTED,
}

internal fun tagClassificationDisposition(category: TasteCategory?, confidence: Float): TagClassificationDisposition {
    return when {
        category == TasteCategory.NOISE -> TagClassificationDisposition.REJECTED
        confidence < MIN_REVIEW_CONFIDENCE -> TagClassificationDisposition.REJECTED
        category == null -> TagClassificationDisposition.REVIEW
        confidence < MIN_AUTOMATIC_CONFIDENCE -> TagClassificationDisposition.REVIEW
        else -> TagClassificationDisposition.AUTOMATIC
    }
}

private fun newRunState(
    planId: String,
    total: Int,
    now: Long,
    candidateFingerprint: String = "",
    personalInputFingerprint: String = "",
) = BackgroundTaskStateEntity(
    taskKey = "ai_tag_classification",
    inputFingerprint = planId,
    phase = "REQUESTING",
    completed = 0,
    total = total,
    batchIndex = 0,
    batchCount = (total + TAG_CLASSIFICATION_BATCH_SIZE - 1) / TAG_CLASSIFICATION_BATCH_SIZE,
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
    candidateFingerprint = candidateFingerprint,
    personalInputFingerprint = personalInputFingerprint,
)

private fun newReadyState(
    planId: String,
    total: Int,
    now: Long,
    candidateFingerprint: String = "",
    personalInputFingerprint: String = "",
) = BackgroundTaskStateEntity(
    taskKey = "ai_tag_classification",
    inputFingerprint = planId,
    phase = "READY",
    completed = 0,
    total = total,
    batchIndex = 0,
    batchCount = (total + TAG_CLASSIFICATION_BATCH_SIZE - 1) / TAG_CLASSIFICATION_BATCH_SIZE,
    automaticCount = 0,
    reviewCount = 0,
    rejectedCount = 0,
    failedCount = 0,
    remaining = total,
    startedAt = null,
    completedAt = null,
    updatedAt = now,
    error = null,
    retryAt = null,
    lastResult = null,
    candidateFingerprint = candidateFingerprint,
    personalInputFingerprint = personalInputFingerprint,
)

private const val TAG_CLASSIFICATION_BATCH_SIZE = 40
private const val MIN_REVIEW_CONFIDENCE = 0.15f
private const val MIN_AUTOMATIC_CONFIDENCE = 0.35f

private data class PlanCounts(
    val total: Int,
    val automatic: Int,
    val review: Int,
    val rejected: Int,
    val manual: Int,
    val failed: Int,
    val pending: Int,
)

private data class MappedBatchResult(
    val dimensions: List<TagDimensionEntity>,
    val planEntries: List<TagClassificationPlanEntity>,
)

private fun buildTagClassificationContent(tags: List<String>): String = buildString {
    appendLine(
        "Return exactly one JSON item for every requested tag; preserve each tag text exactly " +
            "and do not omit or merge items.",
    )
    appendLine(
        "A topic is a semantic genre/category only. Title fragments, romanized names, series names, people, " +
            "characters, staff, developers, publishers, and source names are NOISE/SOURCE/STAFF, never a topic.",
    )
    appendLine(
        "Examples: Steins, Gate, and 空想科学 must not be classified as a topic. " +
            "Only use the requested tag set and valid dimensions.",
    )
    appendLine("请对下列 ${tags.size} 个社区标签逐一分类，为每个标签各输出一条 {tag, dimension}（tag 原样回填）：")
    tags.forEachIndexed { index, tag -> appendLine("${index + 1}. $tag") }
}

private data class BatchClassificationResult(
    val responded: Boolean = false,
    val notConfigured: Boolean = false,
)

/** 一轮分类的结果（供设置页展示反馈）。 */
sealed interface TagClassifyOutcome {

    /** 完成：本轮成功分类并落库 [classified] 个标签（共请求 [requested] 个）。 */
    data class Done(
        val classified: Int,
        val requested: Int,
        val review: Int = 0,
        val rejected: Int = 0,
        val failed: Int = 0,
    ) : TagClassifyOutcome

    /** 无待分类标签（缓存池里的未知标签都已分过，或池为空）。 */
    data object NothingToDo : TagClassifyOutcome

    /** AI 未配置：已回退本地规则，未做任何分类。 */
    data object NotConfigured : TagClassifyOutcome

    /** AI 每批都未返回合法结果（网络失败 / 模型不支持结构化输出 / 低置信），未做任何分类。 */
    data object AiUnresponsive : TagClassifyOutcome
}

/**
 * 把一批 AI 升维输出解析为「清洗后标签 → 维度」列表（纯函数，可单测）。保序、去重；命中规则：
 * 1. AI 回填的 `tag` 清洗后落在本批输入内 → 直接采用；
 * 2. AI 改写、翻译或新增的 tag 不属于请求集合时丢弃，绝不按位置猜测归属；
 * 3. 维度经 [TasteCategory.fromKey]（含近义别名）解析，非法 / 派生维度丢弃（不编造）。
 */
internal fun resolveTagDimensionAssignments(
    output: TagClassifyOutput,
    batch: List<String>,
): List<Pair<String, TasteCategory>> {
    if (output.items.isEmpty()) return emptyList()
    val cleanedBatch = batch.map { TagClassifier.clean(it) }
    val batchKeys = cleanedBatch.toHashSet()
    val seen = HashSet<String>()
    val result = ArrayList<Pair<String, TasteCategory>>(output.items.size)
    output.items.forEach { item ->
        val aiKey = TagClassifier.clean(item.tag)
        val key = aiKey.takeIf { it.isNotEmpty() && it in batchKeys } ?: return@forEach
        if (key.isEmpty() || !seen.add(key)) return@forEach
        val category = TasteCategory.fromKey(item.dimension) ?: return@forEach
        result += key to category
    }
    return result
}
