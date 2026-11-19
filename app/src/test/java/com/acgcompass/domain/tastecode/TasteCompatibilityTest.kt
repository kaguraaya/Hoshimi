package com.acgcompass.domain.tastecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteCompatibilityTest {
    @Suppress("LongParameterList")
    private fun code(
        liked: List<String>,
        avoided: List<String> = emptyList(),
        leniency: Float = 0f,
        works: List<RepresentativeWork> = emptyList(),
        eras: List<Float> = listOf(0f, 0f, 0f, 0.2f, 0.3f, 0.3f, 0.2f, 0f),
        version: Int = 7,
    ) = TasteCode(
        generatedOnDay = 1,
        algorithmVersion = version,
        style = ScoringStyle(100, 7f, 1.2f, leniency, 1f),
        dimensions = mapOf("topic" to 1f, "xp" to 0.5f),
        liked = liked.mapIndexed { i, t -> WeightedTag(t, 1f - i * 0.1f) },
        avoided = avoided.map { WeightedTag(it, 1f) },
        divergence = listOf(WeightedTag("百合", 0.5f)),
        eraShares = eras,
        works = works,
    )

    private fun w(id: Int, rating: Int) = RepresentativeWork(id, rating, RepresentativeReason.TOP_RATED)

    @Test
    fun scoreIsSymmetric() {
        val a = code(listOf("百合", "日常", "音乐"), listOf("后宫"), leniency = 0.5f, works = listOf(w(1, 9), w(2, 8), w(3, 7)))
        val b =
            code(listOf("日常", "科幻"), listOf("致郁"), leniency = -1f, works = listOf(w(1, 7), w(2, 8), w(3, 9), w(4, 10)))
        val ab = TasteCompatibility.compare(a, b)
        val ba = TasteCompatibility.compare(b, a)
        assertEquals(ab.score, ba.score)
        assertEquals(ab.type, ba.type)
        assertEquals(ab.picksForA, ba.picksForB)
    }

    @Test
    fun identicalTastesAreSoulTwins() {
        val a = code(listOf("百合", "日常", "音乐"), listOf("后宫"), works = listOf(w(1, 9), w(2, 8), w(3, 7)))
        val result = TasteCompatibility.compare(a, a.copy(nickname = "Ta"))
        assertTrue(result.score >= 95)
        assertEquals(CompatibilityType.SOUL_TWINS, result.type)
        assertEquals(listOf("百合", "日常", "音乐"), result.sharedLikes.map { it.tag })
        assertEquals(listOf("百合"), result.sharedDivergence)
    }

    @Test
    fun opposedTastesScoreLowAndListClashes() {
        val a = code(listOf("后宫", "热血"), listOf("百合"), eras = listOf(0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f, 0f))
        val b = code(listOf("百合"), listOf("后宫"), leniency = 2.5f)
        val result = TasteCompatibility.compare(a, b)
        assertTrue("score ${result.score}", result.score < 40)
        assertEquals(CompatibilityType.DIFFERENT_WORLDS, result.type)
        assertEquals(listOf("后宫"), result.clashes.first)
        assertEquals(listOf("百合"), result.clashes.second)
    }

    @Test
    fun picksAreHighRatedWorksTheOtherDoesNotList() {
        val a = code(listOf("日常"), works = listOf(w(1, 9), w(5, 10)))
        val b = code(listOf("日常"), works = listOf(w(1, 9), w(6, 8), w(7, 6)))
        val result = TasteCompatibility.compare(a, b)
        assertEquals(listOf(6), result.picksForA.map { it.subjectId })
        assertEquals(listOf(5), result.picksForB.map { it.subjectId })
    }

    @Test
    fun differentAlgorithmVersionsAreFlagged() {
        val a = code(listOf("日常"))
        assertTrue(TasteCompatibility.compare(a, a.copy(algorithmVersion = 8)).algorithmMismatch)
    }

    @Test
    fun tiersCoverEveryScore() {
        assertEquals(CompatibilityTier.MIRROR, CompatibilityTier.of(100))
        assertEquals(CompatibilityTier.DESTINED, CompatibilityTier.of(90))
        assertEquals(CompatibilityTier.PARTIAL, CompatibilityTier.of(55))
        assertEquals(CompatibilityTier.FAR_APART, CompatibilityTier.of(0))
        (0..100).forEach { score -> assertTrue(CompatibilityTier.of(score).minScore <= score) }
    }

    @Test
    fun onlyLikedSkipsTagsTheOtherMentions() {
        val a = code(listOf("百合", "日常", "音乐"), listOf("后宫"))
        val b = code(listOf("日常", "后宫", "热血"), listOf("音乐"))
        val result = TasteCompatibility.compare(a, b)
        assertEquals(listOf("百合"), result.onlyLiked.first.map { it.tag })
        assertEquals(listOf("热血"), result.onlyLiked.second.map { it.tag })
    }
}
