package com.acgcompass.data.taste

import com.acgcompass.domain.taste.TagClassificationPhase
import com.acgcompass.domain.taste.TagClassificationResult
import com.acgcompass.domain.taste.TagClassificationState
import com.acgcompass.domain.taste.TagClassifyProgress
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class TagClassificationCoordinatorTest : StringSpec({
    "stop preserves visible progress and a later start can complete" {
        runTest {
            val firstRun = CompletableDeferred<TagClassifyOutcome>()
            val secondRun = CompletableDeferred<TagClassifyOutcome>(
                TagClassifyOutcome.Done(classified = 5, requested = 5),
            )
            val runs = ArrayDeque<CompletableDeferred<TagClassifyOutcome>>()
            runs.addLast(firstRun)
            runs.addLast(secondRun)
            val task = FakeTagClassificationTask(runs)
            val coordinator = TagClassificationCoordinator.forTest(task, this)

            coordinator.start()
            runCurrent()
            task.mutableProgress.value = TagClassifyProgress(
                done = 40,
                total = 80,
                batchIndex = 2,
                batchCount = 2,
                phase = TagClassificationPhase.WRITING,
            )
            runCurrent()

            coordinator.state.value.active shouldBe true
            coordinator.state.value.completed shouldBe 40

            coordinator.stop()
            advanceUntilIdle()

            coordinator.state.value.active shouldBe false
            coordinator.state.value.lastResult shouldBe TagClassificationResult.STOPPED
            coordinator.state.value.completed shouldBe 40

            task.mutableProgress.value = null
            coordinator.start()
            advanceUntilIdle()

            coordinator.state.value.active shouldBe false
            coordinator.state.value.lastResult shouldBe TagClassificationResult.COMPLETED
            coordinator.state.value.classified shouldBe 5
            task.rebuildCount shouldBe 1
        }
    }

    "partial result restores durable failure and remaining counts" {
        runTest {
            val runs = ArrayDeque<CompletableDeferred<TagClassifyOutcome>>()
            runs.addLast(
                CompletableDeferred(
                    TagClassifyOutcome.Done(classified = 4, requested = 6, failed = 2),
                ),
            )
            val task = FakeTagClassificationTask(
                runs = runs,
                restoredState = TagClassificationState(
                    completed = 4,
                    total = 6,
                    classified = 4,
                    failed = 2,
                    remaining = 2,
                    lastError = "2 个标签请求失败",
                ),
            )
            val coordinator = TagClassificationCoordinator.forTest(task, this)

            coordinator.start()
            advanceUntilIdle()

            coordinator.state.value.lastResult shouldBe TagClassificationResult.PARTIAL
            coordinator.state.value.failed shouldBe 2
            coordinator.state.value.remaining shouldBe 2
            coordinator.state.value.lastError shouldBe "2 个标签请求失败"
        }
    }
})

private class FakeTagClassificationTask(
    private val runs: ArrayDeque<CompletableDeferred<TagClassifyOutcome>>,
    private val restoredState: TagClassificationState? = null,
) : TagClassificationTask {
    val mutableProgress = MutableStateFlow<TagClassifyProgress?>(null)
    override val progress: StateFlow<TagClassifyProgress?> = mutableProgress
    var rebuildCount = 0

    override suspend fun classify(): TagClassifyOutcome = runs.removeFirst().await()

    override suspend fun rebuildProfile() {
        rebuildCount++
    }

    override suspend fun restoreState(): TagClassificationState? = restoredState
}
