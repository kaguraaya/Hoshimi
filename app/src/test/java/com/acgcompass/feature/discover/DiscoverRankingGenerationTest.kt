package com.acgcompass.feature.discover

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DiscoverRankingGenerationTest : StringSpec({
    "only the selected ranking scope and current generation may publish" {
        shouldPublishRankingResult(RankingScope.OVERALL, RankingScope.OVERALL, 4L, 4L) shouldBe true
        shouldPublishRankingResult(RankingScope.YEAR, RankingScope.OVERALL, 4L, 4L) shouldBe false
        shouldPublishRankingResult(RankingScope.OVERALL, RankingScope.OVERALL, 3L, 4L) shouldBe false
    }
})
