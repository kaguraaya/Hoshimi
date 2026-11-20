package com.acgcompass.data.taste

import com.acgcompass.core.common.ApplicationScope
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.domain.taste.ManualTagReview
import com.acgcompass.domain.taste.TagClassificationController
import com.acgcompass.domain.taste.TagClassificationPhase
import com.acgcompass.domain.taste.TagClassificationResult
import com.acgcompass.domain.taste.TagClassificationState
import com.acgcompass.domain.taste.TagClassifyProgress
import com.acgcompass.domain.taste.TasteCategory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Singleton
class TagClassificationCoordinator private constructor(
    private val task: TagClassificationTask,
    private val scope: CoroutineScope,
) : TagClassificationController {
    @Inject
    constructor(
        classifier: AiTagClassifier,
        tasteEngine: TasteEngine,
        @ApplicationScope scope: CoroutineScope,
    ) : this(ProductionTagClassificationTask(classifier, tasteEngine), scope)

    private val _state = MutableStateFlow(TagClassificationState())
    override val state: StateFlow<TagClassificationState> = _state.asStateFlow()
    private var activeJob: Job? = null

    init {
        scope.launch {
            task.restoreState()?.let { restored ->
                _state.value = restored.copy(active = false)
                if (task.shouldResumeAfterRestart()) start()
            }
        }
    }

    override fun start() {
        if (activeJob?.isActive == true) return
        activeJob = scope.launch {
            _state.value = TagClassificationState(active = true)
            val progressJob = task.progress
                .filterNotNull()
                .onEach { progress ->
                    _state.update {
                        it.copy(
                            active = true,
                            phase = progress.phase,
                            completed = progress.done,
                            total = progress.total,
                            batchIndex = progress.batchIndex,
                            batchCount = progress.batchCount,
                            lastResult = null,
                        )
                    }
                }
                .launchIn(this)
            try {
                val outcome = task.classify()
                val result = handleOutcome(outcome)
                if (outcome !is TagClassifyOutcome.Done && outcome != TagClassifyOutcome.NothingToDo) {
                    task.restoreState()?.let { restored -> _state.value = restored.copy(active = true) }
                }
                _state.update { it.copy(lastResult = result) }
            } catch (_: CancellationException) {
                runCatchingCancellable { task.markStopped() }
                _state.update { it.copy(lastResult = TagClassificationResult.STOPPED) }
            } catch (_: Throwable) {
                _state.update { it.copy(lastResult = TagClassificationResult.FAILED) }
            } finally {
                progressJob.cancel()
                _state.update { it.copy(active = false, phase = null) }
                activeJob = null
            }
        }
    }

    private suspend fun handleOutcome(outcome: TagClassifyOutcome): TagClassificationResult = when (outcome) {
        is TagClassifyOutcome.Done -> {
            if (outcome.classified > 0) runCatchingCancellable { task.rebuildProfile() }
            task.restoreState()?.let { restored ->
                _state.value = restored.copy(active = true)
            } ?: _state.update {
                it.copy(
                    completed = outcome.classified + outcome.failed,
                    total = outcome.requested,
                    classified = outcome.classified,
                    failed = outcome.failed,
                    remaining = (outcome.requested - outcome.classified - outcome.failed).coerceAtLeast(0),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            if (outcome.failed > 0) {
                TagClassificationResult.PARTIAL
            } else {
                TagClassificationResult.COMPLETED
            }
        }
        TagClassifyOutcome.NothingToDo -> TagClassificationResult.NOTHING_TO_DO
        TagClassifyOutcome.NotConfigured -> TagClassificationResult.NOT_CONFIGURED
        TagClassifyOutcome.AiUnresponsive -> TagClassificationResult.FAILED
    }

    override fun stop() {
        activeJob?.cancel()
    }

    override fun acknowledgeLastResult() {
        _state.update { it.copy(lastResult = null) }
        scope.launch {
            task.acknowledgeLastResult()
        }
    }

    override suspend fun manualReviewItems(): List<ManualTagReview> = task.manualReviewItems()

    override suspend fun resolveManual(tags: Set<String>, category: TasteCategory) {
        task.resolveManual(tags, category)
        _state.update { it.copy(manualReview = task.manualReviewItems().size) }
        runCatchingCancellable { task.rebuildProfile() }
    }

    internal companion object {
        fun forTest(task: TagClassificationTask, scope: CoroutineScope): TagClassificationCoordinator =
            TagClassificationCoordinator(task, scope)
    }
}

internal interface TagClassificationTask {
    val progress: StateFlow<TagClassifyProgress?>

    suspend fun classify(): TagClassifyOutcome

    suspend fun rebuildProfile()

    suspend fun restoreState(): TagClassificationState? = null

    suspend fun shouldResumeAfterRestart(): Boolean = false

    suspend fun markStopped() = Unit

    suspend fun acknowledgeLastResult() = Unit

    suspend fun manualReviewItems(): List<ManualTagReview> = emptyList()

    suspend fun resolveManual(tags: Set<String>, category: TasteCategory) = Unit
}

private class ProductionTagClassificationTask(
    private val classifier: AiTagClassifier,
    private val tasteEngine: TasteEngine,
) : TagClassificationTask {
    override val progress: StateFlow<TagClassifyProgress?> = classifier.observeProgress()

    override suspend fun classify(): TagClassifyOutcome = classifier.classifyPending()

    override suspend fun rebuildProfile() {
        tasteEngine.rebuildFromCache()
    }

    override suspend fun restoreState(): TagClassificationState? = classifier.persistedState()?.let { state ->
        TagClassificationState(
            active = state.lastResult == null && state.phase in ACTIVE_PHASES && state.remaining > 0,
            phase = runCatchingCancellable { TagClassificationPhase.valueOf(state.phase) }.getOrNull(),
            completed = state.completed,
            total = state.total,
            batchIndex = state.batchIndex,
            batchCount = state.batchCount,
            classified = state.automaticCount,
            manualReview = state.reviewCount,
            rejected = state.rejectedCount,
            failed = state.failedCount,
            remaining = state.remaining,
            lastError = state.error,
            retryAt = state.retryAt,
            startedAt = state.startedAt,
            updatedAt = state.updatedAt,
            lastResult = state.lastResult?.takeUnless { state.notificationAcknowledged }?.let { value ->
                when (value) {
                    "COMPLETED" -> TagClassificationResult.COMPLETED
                    "PARTIAL" -> TagClassificationResult.PARTIAL
                    "FAILED" -> TagClassificationResult.FAILED
                    "STOPPED" -> TagClassificationResult.STOPPED
                    "NOT_CONFIGURED" -> TagClassificationResult.NOT_CONFIGURED
                    else -> null
                }
            },
        )
    }

    override suspend fun shouldResumeAfterRestart(): Boolean = classifier.persistedState()?.let {
        it.lastResult == null && it.phase in ACTIVE_PHASES && it.remaining > 0
    } == true

    override suspend fun markStopped() {
        classifier.markStopped()
    }

    override suspend fun acknowledgeLastResult() {
        classifier.acknowledgeLastResult()
    }

    override suspend fun manualReviewItems(): List<ManualTagReview> = classifier.manualReviewItems()

    override suspend fun resolveManual(tags: Set<String>, category: TasteCategory) {
        classifier.resolveManual(tags, category)
    }
}

private val ACTIVE_PHASES = setOf("REQUESTING", "VALIDATING", "WRITING")
