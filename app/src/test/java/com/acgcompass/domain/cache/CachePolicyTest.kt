package com.acgcompass.domain.cache

import com.acgcompass.domain.model.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe

class CachePolicyTest : StringSpec({
    "count and space limits are normalized to supported ranges" {
        CachePolicy(countLimit = 1).normalizedCountLimit shouldBe 100
        CachePolicy(countLimit = 50_000).normalizedCountLimit shouldBe 10_000
        CachePolicy(spaceLimitBytes = 1).normalizedSpaceLimitBytes shouldBe 50L * 1024L * 1024L
        CachePolicy(spaceLimitBytes = Long.MAX_VALUE).normalizedSpaceLimitBytes shouldBe 5_120L * 1024L * 1024L
    }

    "source allocation is exact and gives integer remainder to Bangumi" {
        CacheSourceAllocation.countTargets(1_001) shouldContainExactly mapOf(
            SourceId.BANGUMI to 801,
            SourceId.TENRAI to 50,
            SourceId.ANILIST to 50,
            SourceId.VNDB to 100,
        )
    }

    "fill delay follows the four utilization bands" {
        CacheSourceAllocation.delayMillis(0.249f) shouldBe 0L
        CacheSourceAllocation.delayMillis(0.25f) shouldBe 800L
        CacheSourceAllocation.delayMillis(0.60f) shouldBe 1_200L
        CacheSourceAllocation.delayMillis(0.85f) shouldBe 1_800L
    }

    "protected entries do not affect automatic count utilization" {
        CacheUsage(publicWorkCount = 250, protectedWorkCount = 50)
            .utilization(CachePolicy(countLimit = 1_000))
            .shouldBeExactly(0.25f)
    }
})
