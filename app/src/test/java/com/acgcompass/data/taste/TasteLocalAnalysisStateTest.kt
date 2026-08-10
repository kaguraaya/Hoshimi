package com.acgcompass.data.taste

import com.acgcompass.data.local.entity.BackgroundTaskStateEntity
import com.acgcompass.domain.taste.TasteLocalAnalysisPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TasteLocalAnalysisStateTest : StringSpec({
    "persisted collecting progress is restored and clamped" {
        val state = taskState(phase = "COLLECTING", completed = -2, total = 10)
            .toTasteLocalAnalysisState()

        state.phase shouldBe TasteLocalAnalysisPhase.COLLECTING
        state.completed shouldBe 0
        state.total shouldBe 10
        state.isRunning shouldBe true
        state.fraction shouldBe 0f
    }

    "building and terminal failure retain the last valid completion time" {
        val building = taskState(phase = "BUILDING", completed = 10, total = 10)
            .toTasteLocalAnalysisState()
        val failed = taskState(phase = "IDLE", completed = 10, total = 10, error = "网络不可用")
            .toTasteLocalAnalysisState()

        building.phase shouldBe TasteLocalAnalysisPhase.BUILDING
        building.isRunning shouldBe true
        failed.phase shouldBe TasteLocalAnalysisPhase.IDLE
        failed.lastSuccessfulAt shouldBe 900L
        failed.error shouldBe "网络不可用"
    }
})

private fun taskState(phase: String, completed: Int, total: Int, error: String? = null) = BackgroundTaskStateEntity(
    taskKey = "local_taste_analysis",
    inputFingerprint = "input",
    phase = phase,
    completed = completed,
    total = total,
    batchIndex = 1,
    batchCount = 1,
    automaticCount = 0,
    reviewCount = 0,
    rejectedCount = 0,
    failedCount = if (error == null) 0 else 1,
    remaining = (total - completed).coerceAtLeast(0),
    startedAt = 100L,
    completedAt = 900L,
    updatedAt = 1_000L,
    error = error,
    retryAt = null,
    lastResult = if (error == null) null else "FAILED",
)
