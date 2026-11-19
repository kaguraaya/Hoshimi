package com.acgcompass.domain.tastecode

import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.CategoryPreference
import com.acgcompass.domain.taste.TasteCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteCodeBuilderTest {
    private val profile = AdvancedTasteProfile(
        categories = mapOf(
            TasteCategory.TOPIC to CategoryPreference(
                positive = mapOf("百合" to 2.0, "日常" to 1.0),
                negative = mapOf("后宫" to -1.5),
            ),
            TasteCategory.XP to CategoryPreference(positive = mapOf("日常" to 0.5)),
            TasteCategory.NOISE to CategoryPreference(positive = mapOf("TV" to 9.0)),
        ),
        algorithmVersion = 7,
    )

    private fun work(id: Int, rating: Int?, community: Double?, year: Int?, at: Long) = TasteCodeWork(
        id,
        rating,
        community,
        year,
        at,
    )

    @Test
    fun tagsMergeAcrossDimensionsAndSkipNoise() {
        val code = TasteCodeBuilder.build(profile, emptyList(), today = 1, nickname = "  ")
        assertEquals(listOf("百合", "日常"), code.liked.map { it.tag })
        assertEquals(1f, code.liked.first().weight)
        assertEquals(listOf("后宫"), code.avoided.map { it.tag })
        assertTrue(code.dimensions.keys.none { it == TasteCategory.NOISE.key })
        assertEquals(null, code.nickname)
    }

    @Test
    fun representativeWorksComeFromThreeGroupsWithoutDuplicates() {
        val works = (1..40).map {
            work(
                it,
                rating = (it % 10) + 1,
                community = 7.0,
                year = 2000 + it % 25,
                at = it.toLong(),
            )
        }
        val picked = TasteCodeBuilder.representativeWorks(works)
        assertEquals(TasteCode.MAX_WORKS, picked.size)
        assertEquals(picked.size, picked.map { it.subjectId }.toSet().size)
        val top = picked.take(15)
        assertTrue(top.all { it.reason == RepresentativeReason.TOP_RATED && it.rating >= 7 })
        assertEquals(top.map { it.rating }.sortedDescending(), top.map { it.rating })
        assertTrue(picked.any { it.reason == RepresentativeReason.RECENT })
    }

    @Test
    fun styleAndEraFollowTheCollection() {
        val works = listOf(work(1, 8, 7.0, 1995, 1), work(2, 6, 7.0, 2021, 2), work(3, null, null, 2021, 3))
        val code = TasteCodeBuilder.build(profile, works, today = 1)
        assertEquals(2, code.style.ratedCount)
        assertEquals(7f, code.style.mean, 0.001f)
        assertEquals(0f, code.style.leniency, 0.001f)
        assertEquals(1f / 3, code.eraShares[1], 0.001f)
        assertEquals(2f / 3, code.eraShares[6], 0.001f)
    }

    @Test
    fun creatorsDoNotCrowdOutGenres() {
        val cv = (1..30).associate { "声优$it" to 10.0 }
        val topics = (1..20).associate { "题材$it" to 1.0 }
        val crowded = AdvancedTasteProfile(
            categories = mapOf(
                TasteCategory.CV to CategoryPreference(positive = cv),
                TasteCategory.TOPIC to CategoryPreference(positive = topics),
            ),
            algorithmVersion = 7,
        )
        val code = TasteCodeBuilder.build(crowded, emptyList(), today = 1)
        assertEquals(TasteCode.MAX_LIKED, code.liked.size)
        // 题材组占一半名额；角色属性组没有候选，名额由其它组补上。
        assertTrue(code.liked.count { it.category == "topic" } >= TasteCode.MAX_LIKED / 2)
        assertTrue(code.liked.all { it.category == "topic" || it.category == "cv" })
    }

    @Test
    fun dimensionsReflectStrengthNotTagCount() {
        // 40 个弱 staff 标签的总和远大于 3 个强题材标签，但维度强度看的是最强几个标签和维度权重。
        val profile = AdvancedTasteProfile(
            categories = mapOf(
                TasteCategory.STAFF to CategoryPreference(positive = (1..40).associate { "staff$it" to 0.3 }),
                TasteCategory.TOPIC to CategoryPreference(positive = mapOf("百合" to 1.0, "日常" to 0.8, "音乐" to 0.7)),
            ),
            algorithmVersion = 7,
        )
        val dims = TasteCodeBuilder.build(profile, emptyList(), today = 1).dimensions
        assertEquals(1f, dims.getValue("topic"))
        assertTrue(dims.getValue("staff") < 0.3f)
    }
}
