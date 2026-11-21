package com.acgcompass.domain.recommend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class InterestSignalsTest : StringSpec({
    val day = 24L * 60 * 60 * 1000
    val now = 1_000L * day

    "weights halve every 90 days" {
        InterestSignals.decay(0L) shouldBe (1.0 plusOrMinus 1e-12)
        InterestSignals.decay(90 * day) shouldBe (0.5 plusOrMinus 1e-12)
        InterestSignals.decay(-5L) shouldBe (1.0 plusOrMinus 1e-12)
        InterestSignals.feedback(InterestSignals.Kind.DROPPED, setOf("a"), now - 180 * day, now).weight shouldBe
            (0.25 plusOrMinus 1e-12)
    }

    "collection statuses map to interest kinds; finished or rated items are not interest signals" {
        InterestSignals.statusKind("想看") shouldBe InterestSignals.Kind.WISH
        InterestSignals.statusKind("抛弃") shouldBe InterestSignals.Kind.DROPPED
        InterestSignals.statusKind("看过").shouldBeNull()
        InterestSignals.statusKind(null).shouldBeNull()
    }

    "dropped titles pull a tag down, wished titles push it up, and fresh signals outweigh stale ones" {
        val signals = listOf(
            InterestSignals.feedback(InterestSignals.Kind.DROPPED, setOf("后宫"), now, now),
            InterestSignals.feedback(InterestSignals.Kind.DROPPED, setOf("后宫"), now - day, now),
            InterestSignals.feedback(InterestSignals.Kind.WISH, setOf("悬疑"), now, now),
            InterestSignals.feedback(InterestSignals.Kind.EXPLORE_LIKE, setOf("悬疑"), now - 2 * day, now),
            InterestSignals.feedback(InterestSignals.Kind.EXPLORE_LIKE, setOf("后宫"), now - 720 * day, now),
        )
        val model = ExploreRanker.feedbackModel(signals)

        model.adjust(setOf("后宫")) shouldBeLessThan 0.0
        model.adjust(setOf("悬疑")) shouldBeGreaterThan 0.0
    }

    "the default feedback weights keep the previous counting (skip = half a like)" {
        ExploreRanker.Feedback(setOf("a"), liked = true).weight shouldBe 1.0
        ExploreRanker.Feedback(setOf("a"), liked = false).weight shouldBe 0.5
    }
})
