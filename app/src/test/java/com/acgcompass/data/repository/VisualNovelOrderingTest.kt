package com.acgcompass.data.repository

import com.acgcompass.domain.visualnovel.VisualNovelSort
import com.acgcompass.feature.visualnovel.stableVisualNovelRandomSeed
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class VisualNovelOrderingTest : StringSpec({
    "each deterministic sort has its own seed-independent cache scope" {
        VisualNovelSort.entries
            .filterNot { it == VisualNovelSort.RANDOM }
            .forEach { sort ->
                visualNovelFeaturedScope(sort, 1L) shouldBe visualNovelFeaturedScope(sort, 99L)
            }

        VisualNovelSort.entries
            .filterNot { it == VisualNovelSort.RANDOM }
            .map { visualNovelFeaturedScope(it, 1L) }
            .distinct()
            .size shouldBe VisualNovelSort.entries.size - 1
    }

    "random cache scope changes only when its stable seed changes" {
        val first = visualNovelFeaturedScope(VisualNovelSort.RANDOM, 42L)

        first shouldBe visualNovelFeaturedScope(VisualNovelSort.RANDOM, 42L)
        first shouldNotBe visualNovelFeaturedScope(VisualNovelSort.RANDOM, 43L)
    }

    "random seed is stable for one day and advances with manual refresh generation" {
        val day = 20_000L

        stableVisualNovelRandomSeed(day, 0L) shouldBe stableVisualNovelRandomSeed(day, 0L)
        stableVisualNovelRandomSeed(day, 1L) shouldBe stableVisualNovelRandomSeed(day, 0L) + 1L
        stableVisualNovelRandomSeed(day + 1L, 0L) shouldNotBe stableVisualNovelRandomSeed(day, 1L)
    }
})
