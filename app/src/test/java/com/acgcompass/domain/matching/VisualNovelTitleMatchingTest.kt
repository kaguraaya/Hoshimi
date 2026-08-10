package com.acgcompass.domain.matching

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class VisualNovelTitleMatchingTest : StringSpec({
    "same visual novel title remains a high-confidence candidate" {
        visualNovelMatchConfidence("STEINS;GATE", "Steins Gate", true) shouldBeGreaterThan 0.92
    }

    "chapter and sequel markers must not be collapsed" {
        visualNovelMatchConfidence("Game 第一章", "Game 第二章", true) shouldBe 0.0
        visualNovelMatchConfidence("Game I", "Game II", true) shouldBe 0.0
        visualNovelMatchConfidence("Game", "Game II", true) shouldBe 0.0
    }

    "Greek variants and editions must not be collapsed" {
        visualNovelMatchConfidence("Chaos α", "Chaos β", true) shouldBe 0.0
        visualNovelMatchConfidence("Story", "Story Remake", true) shouldBe 0.0
    }
})
