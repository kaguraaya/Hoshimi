package com.acgcompass.data.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly

class StableCandidateSelectionTest : StringSpec({
    "one remaining tag is returned immediately without an accumulation threshold" {
        selectPendingCandidateTags(
            frequency = mapOf("only" to 1),
            rawTags = mapOf("only" to "last tag"),
            excluded = emptySet(),
            limit = 300,
        ) shouldContainExactly listOf("last tag")
    }

    "terminal tags are removed before applying the plan limit" {
        selectPendingCandidateTags(
            frequency = linkedMapOf("done" to 100, "first" to 20, "second" to 10),
            rawTags = mapOf("done" to "done", "first" to "first", "second" to "second"),
            excluded = setOf("done"),
            limit = 2,
        ) shouldContainExactly listOf("first", "second")
    }

    "the final tag remains visible after a capped plan completes" {
        val frequency = linkedMapOf("first" to 30, "second" to 20, "last" to 10)
        val rawTags = frequency.keys.associateWith { it }
        val firstPlan = selectPendingCandidateTags(frequency, rawTags, excluded = emptySet(), limit = 2)

        selectPendingCandidateTags(
            frequency = frequency,
            rawTags = rawTags,
            excluded = firstPlan.toSet(),
            limit = 2,
        ) shouldContainExactly listOf("last")
    }
})
