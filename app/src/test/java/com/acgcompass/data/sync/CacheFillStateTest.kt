package com.acgcompass.data.sync

import com.acgcompass.data.local.entity.CacheFillStateEntity
import com.acgcompass.domain.model.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CacheFillStateTest : StringSpec({
    "Tenrai failures advance through the conservative cooldown schedule" {
        var previous: CacheFillStateEntity? = null
        val expectedCooldowns = listOf(30_000L, 120_000L, 300_000L, 900_000L, 900_000L)

        expectedCooldowns.forEachIndexed { index, cooldown ->
            val now = 10_000L + index
            previous = nextCacheFillState(
                source = SourceId.TENRAI,
                batch = failedBatch(nextPage = 7),
                previous = previous,
                now = now,
            )

            previous?.consecutiveFailures shouldBe index + 1
            previous?.cooldownUntil shouldBe now + cooldown
            previous?.nextPage shouldBe 7
        }
    }

    "a successful probe clears failures and records the latest success" {
        val previous = nextCacheFillState(
            source = SourceId.TENRAI,
            batch = failedBatch(nextPage = 4),
            previous = null,
            now = 1_000L,
        )
        val recovered = nextCacheFillState(
            source = SourceId.TENRAI,
            batch = CacheFillBatch(emptyList<Any>(), 5, false, RESULT_TARGET_REACHED),
            previous = previous,
            now = 2_000L,
        )

        recovered.consecutiveFailures shouldBe 0
        recovered.cooldownUntil shouldBe null
        recovered.lastSuccessAt shouldBe 2_000L
        recovered.failureReason shouldBe null
    }

    "another supplementary source failure does not apply the Tenrai circuit breaker" {
        val state = nextCacheFillState(
            source = SourceId.ANILIST,
            batch = failedBatch(nextPage = 3),
            previous = null,
            now = 1_000L,
        )

        state.consecutiveFailures shouldBe 1
        state.cooldownUntil shouldBe null
    }
})

private fun failedBatch(nextPage: Int): CacheFillBatch<Any> = CacheFillBatch(
    items = emptyList(),
    nextPage = nextPage,
    exhausted = false,
    result = RESULT_FAILED,
    failureReason = "limited",
)
