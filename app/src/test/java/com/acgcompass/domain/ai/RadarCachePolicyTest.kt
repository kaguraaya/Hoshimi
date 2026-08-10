package com.acgcompass.domain.ai

import com.acgcompass.domain.usecase.RadarRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class RadarCachePolicyTest : StringSpec({
    val base = RadarRequest(
        workId = "1",
        title = "Steins;Gate",
        summary = "Time travel story",
        mediaType = "ANIME",
        tags = listOf("科幻", "时间旅行"),
        publicReviews = listOf("old review"),
        communityScore = 9.1,
        communityVotes = 10_000,
    )

    "ordinary score and review changes do not invalidate the radar" {
        RadarCachePolicy.fingerprint(base) shouldBe RadarCachePolicy.fingerprint(
            base.copy(
                publicReviews = listOf("new review"),
                communityScore = 8.9,
                communityVotes = 20_000,
            ),
        )
    }

    "title summary media type or tags invalidate the radar" {
        val fingerprint = RadarCachePolicy.fingerprint(base)
        RadarCachePolicy.fingerprint(base.copy(title = "Another title")) shouldNotBe fingerprint
        RadarCachePolicy.fingerprint(base.copy(summary = "Changed")) shouldNotBe fingerprint
        RadarCachePolicy.fingerprint(base.copy(mediaType = "GAME")) shouldNotBe fingerprint
        RadarCachePolicy.fingerprint(base.copy(tags = listOf("悬疑"))) shouldNotBe fingerprint
    }

    "tag order casing and duplicates are normalized" {
        RadarCachePolicy.fingerprint(base.copy(tags = listOf("时间旅行", "科幻", "科幻"))) shouldBe
            RadarCachePolicy.fingerprint(base.copy(tags = listOf(" 科幻 ", "时间旅行")))
    }
})
