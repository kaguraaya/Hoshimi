package com.acgcompass.data.remote.tenrai

import com.acgcompass.core.network.NetworkJson
import com.acgcompass.data.remote.jikan.JikanAnimeSearchResponseDto
import com.acgcompass.data.remote.jikan.toRatingEntry
import com.acgcompass.data.remote.jikan.toWorkMatch
import com.acgcompass.domain.model.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromString

class TenraiCompatibilityTest : StringSpec({
    "Tenrai list response maps ratings and broadcast fields through the compatible DTO" {
        val response = NetworkJson.instance.decodeFromString<JikanAnimeSearchResponseDto>(
            """
            {
              "pagination": {"current_page": 1, "has_next_page": false},
              "data": [{
                "mal_id": 9253,
                "title": "Steins;Gate",
                "title_japanese": "シュタインズ・ゲート",
                "score": 9.07,
                "scored_by": 1400000,
                "broadcast": {"day": "Wednesdays", "time": "23:30", "timezone": "Asia/Tokyo"},
                "future_field": "ignored"
              }]
            }
            """.trimIndent(),
        )

        val item = response.data.single()
        item.toWorkMatch(source = SourceId.TENRAI).sourceTag shouldBe SourceId.TENRAI
        item.toRatingEntry()?.score shouldBe 9.07f
        item.broadcast?.day shouldBe "Wednesdays"
        item.broadcast?.time shouldBe "23:30"
        item.broadcast?.timezone shouldBe "Asia/Tokyo"
    }

    "Tenrai missing score is not converted into a zero rating" {
        val response = NetworkJson.instance.decodeFromString<JikanAnimeSearchResponseDto>(
            """{"data":[{"mal_id":1,"title":"No score","score":null,"scored_by":0}]}""",
        )

        response.data.single().toRatingEntry() shouldBe null
    }
})
