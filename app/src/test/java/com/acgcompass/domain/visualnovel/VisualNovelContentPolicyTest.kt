package com.acgcompass.domain.visualnovel

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VisualNovelContentPolicyTest : StringSpec({
    "R18 disabled hides adult rows even when they already exist in cache" {
        VisualNovelContentPolicy.canDisplay(adult = true, allowAdult = false) shouldBe false
        VisualNovelContentPolicy.canDisplay(adult = false, allowAdult = false) shouldBe true
    }

    "R18 enabled permits both adult and general rows" {
        VisualNovelContentPolicy.canDisplay(adult = true, allowAdult = true) shouldBe true
        VisualNovelContentPolicy.canDisplay(adult = false, allowAdult = true) shouldBe true
    }
})
