package com.acgcompass.feature.detail

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DetailRecordEditTest : StringSpec({
    fun edit(status: String? = null, progress: Int? = null) = RecordEdit(
        statusLabel = status,
        rating = null,
        progress = progress,
        comment = null,
        tags = null,
        isPrivate = false,
    )

    "progress is clamped to the episode count and finishing marks it watched" {
        edit(progress = 30).resolveProgress(totalEpisodes = 12) shouldBe (12 to "看过")
    }

    "partial progress marks it watching" {
        edit(progress = 3).resolveProgress(totalEpisodes = 12) shouldBe (3 to "在看")
    }

    "no progress leaves the status empty and negative progress becomes zero" {
        edit().resolveProgress(totalEpisodes = 12) shouldBe (null to null)
        edit(progress = -2).resolveProgress(totalEpisodes = null) shouldBe (0 to null)
    }

    "unknown episode count never infers watched" {
        edit(progress = 99).resolveProgress(totalEpisodes = 0) shouldBe (99 to "在看")
    }

    "an explicit status wins over the inferred one" {
        edit(status = "搁置", progress = 12).resolveProgress(totalEpisodes = 12) shouldBe (12 to "搁置")
    }

    "status labels map to Bangumi collection types" {
        listOf("想看", "看过", "在看", "搁置", "抛弃", "其它").map(::bangumiTypeOf) shouldBe listOf(1, 2, 3, 4, 5, null)
    }
})
