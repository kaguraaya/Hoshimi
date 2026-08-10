package com.acgcompass.data.taste

import com.acgcompass.domain.taste.TasteCategory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TagClassificationDispositionTest : StringSpec({
    "high confidence valid category is automatic" {
        tagClassificationDisposition(TasteCategory.TOPIC, 0.35f) shouldBe
            TagClassificationDisposition.AUTOMATIC
    }

    "low confidence category waits for manual review" {
        tagClassificationDisposition(TasteCategory.TOPIC, 0.2f) shouldBe
            TagClassificationDisposition.REVIEW
    }

    "omitted or invalid model assignment waits for manual review" {
        tagClassificationDisposition(null, 0.9f) shouldBe TagClassificationDisposition.REVIEW
    }

    "very low confidence and explicit noise are terminal rejections" {
        tagClassificationDisposition(TasteCategory.TOPIC, 0.14f) shouldBe
            TagClassificationDisposition.REJECTED
        tagClassificationDisposition(TasteCategory.NOISE, 0.9f) shouldBe
            TagClassificationDisposition.REJECTED
    }
})
