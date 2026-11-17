package com.acgcompass.data.sync

import com.acgcompass.data.local.entity.RatingEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RatingBackfillTargetsTest : StringSpec({
    val now = 10_000_000_000L

    fun work(id: String, type: MediaType = MediaType.ANIME) = WorkEntity(
        id = id,
        canonicalTitle = id,
        titleJa = null,
        titleRomaji = null,
        titleEn = null,
        aliases = emptyList(),
        mediaType = type.name,
        year = null,
        status = "UNKNOWN",
        episodes = null,
        episodeMinutes = null,
        volumes = null,
        estPlayMinutes = null,
        coverUrl = null,
        primarySource = SourceId.BANGUMI.name,
        completionCostBucket = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    fun rating(workId: String, source: SourceId, score: Float, votes: Int) =
        RatingEntity("$workId-$source", workId, source.name, score, votes, null, now - 1L, false)

    "works with one or no rating are picked, personal ones first, then by votes" {
        val works = listOf(work("a"), work("b"), work("c"), work("d"), work("vn", MediaType.VN))
        val ratings = listOf(
            rating("a", SourceId.BANGUMI, 7.5f, 100),
            rating("b", SourceId.BANGUMI, 8f, 9_000),
            rating("c", SourceId.BANGUMI, 7f, 50),
            rating("c", SourceId.ANILIST, 7.2f, 50),
            rating("vn", SourceId.BANGUMI, 7f, 50),
        )
        val picked = selectBackfillTargets(works, ratings, setOf("a"), emptySet(), now, limit = 10)
        // c 已有两个平台的分；vn 不是动画；d 还没有任何评分记录也会被选中。
        picked shouldBe listOf("a", "b", "d")
    }

    "works tried recently or whose other platforms were just checked are skipped" {
        val works = listOf(work("a"), work("b"))
        val ratings = listOf(
            rating("a", SourceId.BANGUMI, 7.5f, 100),
            rating("b", SourceId.BANGUMI, 8f, 900),
            rating("b", SourceId.ANILIST, 0f, 0).copy(missing = true, fetchedAt = now - 1_000L),
        )
        selectBackfillTargets(works, ratings, emptySet(), setOf("a"), now, limit = 10) shouldBe emptyList()
    }
})
