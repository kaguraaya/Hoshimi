package com.acgcompass

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * Trivial scaffolding sanity test. Confirms the kotest runner and kotest-property generator
 * wiring compile and execute. Real correctness properties  arrive with their
 * feature tasks.
 */
class SanityTest : StringSpec({

    "kotest assertions are wired" {
        (2 + 2) shouldBe 4
    }

    "kotest-property generators are wired (addition is commutative)" {
        // Fast smoke check: a small sample is enough for the wiring sanity test.
        checkAll(PropTestConfig(iterations = 20), Arb.int(), Arb.int()) { a, b ->
            (a + b) shouldBe (b + a)
        }
    }
})
