package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** 年代档在理由里的写法：含今年的那一档写「近几年」，更早的写年份范围。 */
class EraLabelTest : StringSpec({
    "the bucket holding this year reads as recent years" {
        TagClassifier.eraLabel("2025-2029", currentYear = 2026) shouldBe "近几年"
    }

    "older buckets read as a year range" {
        TagClassifier.eraLabel("2020-2024", currentYear = 2026) shouldBe "2020–2024 年"
        TagClassifier.eraLabel("2010-2014", currentYear = 2026) shouldBe "2010–2014 年"
    }

    "time tags that are not buckets stay as they are" {
        TagClassifier.eraLabel("夏季", currentYear = 2026) shouldBe "夏季"
    }
})
