package com.acgcompass.data.sync

import com.acgcompass.domain.model.RatingBackfillOutcome
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RatingBackfillPolicyTest : StringSpec({
    val now = 1_000_000_000L
    val hour = 60L * 60 * 1000

    "checked works wait a week and reset the failure count" {
        val failing = RatingBackfillPolicy.next("1", null, RatingBackfillOutcome.TRANSIENT_FAILURE, now)
        val checked = RatingBackfillPolicy.next("1", failing, RatingBackfillOutcome.CHECKED, now + hour)
        checked.failureCount shouldBe 0
        checked.lastCheckedAt shouldBe now + hour
        checked.nextAttemptAt shouldBe now + hour + RatingBackfillPolicy.CHECKED_RETRY_MS
    }

    "network failures retry within hours, growing to at most a day" {
        var state = RatingBackfillPolicy.next("1", null, RatingBackfillOutcome.TRANSIENT_FAILURE, now)
        state.nextAttemptAt - now shouldBe 1 * hour
        state = RatingBackfillPolicy.next("1", state, RatingBackfillOutcome.TRANSIENT_FAILURE, now)
        state.nextAttemptAt - now shouldBe 3 * hour
        repeat(10) { state = RatingBackfillPolicy.next("1", state, RatingBackfillOutcome.TRANSIENT_FAILURE, now) }
        state.nextAttemptAt - now shouldBe 24 * hour
        state.failureCount shouldBe 12
    }

    "a failure keeps the last successful check time" {
        val checked = RatingBackfillPolicy.next("1", null, RatingBackfillOutcome.CHECKED, now)
        val failed = RatingBackfillPolicy.next("1", checked, RatingBackfillOutcome.FAILED, now + hour)
        failed.lastCheckedAt shouldBe now
        failed.nextAttemptAt shouldBe now + hour + RatingBackfillPolicy.FAILED_RETRY_MS
    }

    "legacy attempts become checked rows scheduled a week after the old time" {
        val rows = RatingBackfillPolicy.fromLegacy(mapOf("7" to now))
        rows.single().nextAttemptAt shouldBe now + RatingBackfillPolicy.CHECKED_RETRY_MS
        rows.single().lastOutcome shouldBe RatingBackfillOutcome.CHECKED.name
    }
})
