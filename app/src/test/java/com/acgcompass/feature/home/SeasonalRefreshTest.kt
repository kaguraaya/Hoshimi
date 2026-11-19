package com.acgcompass.feature.home

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonalRefreshTest {
    private fun work(id: String) =
        Work(id = id, titles = Titles(canonical = id), mediaType = MediaType.ANIME, primarySource = SourceId.BANGUMI)

    private fun rating(score: Float, votes: Int) = RatingEntry(score = score, voteCount = votes)

    @Test
    fun ratingTextShowsVotes() {
        assertEquals("Bangumi 8.1 · 1.2 万人评", seasonalRatingText(8.1f, 12_345))
        assertEquals("Bangumi 7.0 · 856 人评", seasonalRatingText(7f, 856))
        assertEquals("Bangumi 6.5", seasonalRatingText(6.5f, 0))
    }

    @Test
    fun picksFromPopularOrLikedAtRandom() {
        // 20 部候选：评分人数 1..20；热度前一半是 11..20 号。
        val candidates = (1..20).map { work("$it") to rating(7f, it * 100) }
        val matches = mapOf("2" to 90, "3" to 60)
        repeat(20) { seed ->
            val picked = pickSeasonal(candidates, matches, kotlin.random.Random(seed)).map { it.first.id }
            assertEquals(6, picked.size)
            assertEquals(picked.size, picked.toSet().size)
            // 只从「热度前 12 部（最少 12 部）」和「匹配度 ≥ 72」里挑：3 号匹配度 60 且热度靠后，不会出现。
            assert(picked.none { it == "3" || it == "1" }) { picked }
        }
        val a = pickSeasonal(candidates, matches, kotlin.random.Random(1)).map { it.first.id }
        val b = pickSeasonal(candidates, matches, kotlin.random.Random(2)).map { it.first.id }
        assert(a != b) { "different seeds should give a different batch" }
    }

    @Test
    fun skipsFewVotesAndLowScoresWhenThereAreEnough() {
        val good = (1..10).map { work("g$it") to rating(7f, 500 + it) }
        val fewVotes = (1..5).map { work("f$it") to rating(9f, 3) }
        val lowScore = (1..5).map { work("l$it") to rating(4.5f, 5_000) }
        repeat(10) { seed ->
            val picked = pickSeasonal(good + fewVotes + lowScore, emptyMap(), kotlin.random.Random(seed))
            assert(picked.all { it.first.id.startsWith("g") }) { picked.map { it.first.id } }
        }
        // 质量够的不够 6 部时，用评分人数多的补满。
        val picked = pickSeasonal(good.take(3) + lowScore, emptyMap(), kotlin.random.Random(1))
        assertEquals(6, picked.size)
    }

    @Test
    fun prefersWorksNotShownRecently() {
        val candidates = (1..12).map { work("$it") to rating(7f, 1_000 + it) }
        val shown = (1..6).map { "$it" }.toSet()
        repeat(10) { seed ->
            val picked = pickSeasonal(candidates, emptyMap(), kotlin.random.Random(seed), exclude = shown)
            assert(picked.none { it.first.id in shown }) { picked.map { it.first.id } }
        }
    }
}
