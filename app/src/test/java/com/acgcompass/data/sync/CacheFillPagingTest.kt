package com.acgcompass.data.sync

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class CacheFillPagingTest : StringSpec({
    "a 3000 item target is not limited by a fixed page count" {
        var requests = 0
        val result = collectCacheFillPages(
            config = CacheFillPagingConfig(targetCount = 3_000, existingCount = 1_000, startPage = 21),
            keyOf = Int::toString,
            fetchPage = { page ->
                requests++
                val first = (page - 1) * 50
                CacheCandidatePageResult.Page((first until first + 50).toList(), hasMore = true)
            },
        )

        result.items.size shouldBe 2_000
        requests shouldBe 40
        result.nextPage shouldBe 61
        result.result shouldBe RESULT_TARGET_REACHED
        result.exhausted shouldBe false
    }

    "duplicates do not satisfy the requested number of actual slots" {
        val pages = mapOf(
            1 to listOf("a", "a", "b"),
            2 to listOf("b", "c", "d"),
        )
        val result = collectCacheFillPages(
            config = CacheFillPagingConfig(targetCount = 4, existingCount = 0, startPage = 1),
            keyOf = { it },
            fetchPage = { page -> CacheCandidatePageResult.Page(pages.getValue(page), hasMore = page < 2) },
        )

        result.items shouldContainExactly listOf("a", "b", "c", "d")
        result.nextPage shouldBe 3
    }

    "a failed page keeps its cursor for the next run" {
        val result = collectCacheFillPages(
            config = CacheFillPagingConfig(targetCount = 10, existingCount = 3, startPage = 7),
            keyOf = { it },
            fetchPage = { CacheCandidatePageResult.Failure("rate limited") },
        )

        result.nextPage shouldBe 7
        result.exhausted shouldBe false
        result.result shouldBe RESULT_FAILED
        result.failureReason shouldBe "rate limited"
    }

    "an empty filtered page continues when the source reports more pages" {
        val result = collectCacheFillPages(
            config = CacheFillPagingConfig(targetCount = 1, existingCount = 0, startPage = 1),
            keyOf = { it },
            fetchPage = { page ->
                if (page == 1) {
                    CacheCandidatePageResult.Page(emptyList(), hasMore = true)
                } else {
                    CacheCandidatePageResult.Page(listOf("safe"), hasMore = false)
                }
            },
        )

        result.items shouldContainExactly listOf("safe")
        result.result shouldBe RESULT_TARGET_REACHED
    }

    "source exhaustion is recorded only when hasMore is false" {
        val result = collectCacheFillPages(
            config = CacheFillPagingConfig(targetCount = 5, existingCount = 2, startPage = 3),
            keyOf = { it },
            fetchPage = { CacheCandidatePageResult.Page(listOf("last"), hasMore = false) },
        )

        result.items shouldContainExactly listOf("last")
        result.nextPage shouldBe 4
        result.exhausted shouldBe true
        result.result shouldBe RESULT_EXHAUSTED
    }
})
