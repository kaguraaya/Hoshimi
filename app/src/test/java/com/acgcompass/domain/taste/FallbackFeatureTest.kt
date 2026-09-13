package com.acgcompass.domain.taste

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random

/** 没有 Bangumi 条目的作品：用作品自己的标签与其它源的评分出分，而不是一直「正在获取标签」。 */
class FallbackFeatureTest : StringSpec({

    val work = Work(
        id = "anilist:12345",
        titles = Titles(canonical = "Cyber Romance", cn = "赛博恋曲"),
        mediaType = MediaType.ANIME,
        primarySource = SourceId.ANILIST,
        units = Units(episodes = 12, episodeMinutes = 24),
        tags = listOf("恋爱", "战斗", "搞笑", "科幻", "血腥", "赛博朋克").map { Tag(TagCategory.CONTENT, it) },
    )

    "tags keep their order as descending weights and AniList scores are scaled to ten points" {
        val feature = work.toFallbackFeature(
            RatingAggregate(perSource = mapOf(SourceId.ANILIST to RatingEntry(score = 78f, voteCount = 5200))),
        )
        feature.tagCounts.map { it.name } shouldBe listOf("恋爱", "战斗", "搞笑", "科幻", "血腥", "赛博朋克")
        feature.tagCounts.zipWithNext().all { (a, b) -> a.count > b.count } shouldBe true
        feature.bangumiScore shouldBe 7.8f
        feature.bangumiVotes shouldBe 5200
        feature.eps shouldBe 12
        feature.titleVariants.first() shouldBe "赛博恋曲"
    }

    "a Bangumi score is preferred when present" {
        val feature = work.toFallbackFeature(
            RatingAggregate(
                perSource = mapOf(
                    SourceId.ANILIST to RatingEntry(score = 78f, voteCount = 5200),
                    SourceId.BANGUMI to RatingEntry(score = 7.2f, voteCount = 900),
                ),
            ),
        )
        feature.bangumiScore shouldBe 7.2f
    }

    "the fallback feature produces a match and a rating prediction" {
        val r = Random(3)
        val pool = listOf("恋爱", "搞笑", "日常", "校园", "科幻", "战斗", "奇幻", "音乐")
        val samples = (0 until 40).map { i ->
            val community = 6.2f + r.nextInt(20) / 10f
            val tags = pool.shuffled(r).take(4)
            TasteSample(
                subjectId = "s$i",
                rating = (community + if ("恋爱" in tags) 1f else 0f).toInt().coerceIn(1, 10),
                comment = null,
                updatedAtMillis = 1_000L * i,
                feature = WorkFeature(
                    subjectId = "s$i",
                    tagCounts = tags.mapIndexed { k, t -> TagCount(t, 100 - k * 10) },
                    bangumiScore = community,
                    bangumiVotes = 2000,
                    eps = 12,
                    titleVariants = listOf("s$i"),
                ),
            )
        }
        val profile = BuildTasteProfileUseCase()(samples, 1_000_000L)
        val feature = work.toFallbackFeature(
            RatingAggregate(perSource = mapOf(SourceId.ANILIST to RatingEntry(score = 78f, voteCount = 5200))),
        )
        val match = ComputeTasteMatchUseCase()(feature, profile)
        match.prediction shouldNotBe null
        (match.score in 1..99) shouldBe true
    }
})
