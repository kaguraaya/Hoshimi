package com.acgcompass.domain.ai

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class VndbTranslationCachePolicyTest : StringSpec({
    "fingerprint ignores surrounding whitespace but changes with the source text" {
        VndbTranslationCachePolicy.fingerprint("  description  ") shouldBe
            VndbTranslationCachePolicy.fingerprint("description")
        VndbTranslationCachePolicy.fingerprint("description 2") shouldNotBe
            VndbTranslationCachePolicy.fingerprint("description")
    }

    "cached translation requires both the same input and prompt version" {
        val source = "A science fiction visual novel."
        val fingerprint = VndbTranslationCachePolicy.fingerprint(source)
        VndbTranslationCachePolicy.isReusable(
            fingerprint,
            VndbTranslationCachePolicy.PROMPT_VERSION,
            source,
        ) shouldBe true
        VndbTranslationCachePolicy.isReusable(fingerprint, 0, source) shouldBe false
        VndbTranslationCachePolicy.isReusable(fingerprint, 1, "$source Changed") shouldBe false
    }
})
