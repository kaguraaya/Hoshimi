package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * v4 / v5 口味算法守卫：社区分量纲、候选证据量收缩、样本选择、预计满意度。
 * 全部为关系型断言（谁应该高于谁 / 落在哪个区间），不固定具体分数，避免把参数过拟合进测试。
 */
class TasteV4GuardTest : StringSpec({

    val build = BuildTasteProfileUseCase()
    val compute = ComputeTasteMatchUseCase()
    val now = 1_700_000_000_000L
    val day = 24L * 3600 * 1000

    fun feature(id: String, tags: List<Pair<String, Int>>, score: Float? = 7.5f, votes: Int = 5000) = WorkFeature(
        subjectId = id,
        tagCounts = tags.map { TagCount(it.first, it.second) },
        bangumiScore = score,
        bangumiVotes = votes,
        titleVariants = listOf(id),
    )

    val genres = listOf("奇幻", "异世界", "战斗", "冒险", "智斗", "魔法", "恋爱", "日常", "校园", "治愈", "科幻", "悬疑")

    /** 造一个用户：[ratingOf] 决定他给一部「社区分为 community」的作品打几分。 */
    fun samples(n: Int, ratingOf: (community: Float, index: Int) -> Int): List<TasteSample> = (0 until n).map { i ->
        val community = 5.5f + (i % 8) * 0.45f
        val tags = genres.shuffled(Random(i.toLong())).take(4).mapIndexed { j, g -> g to (40 - j * 6) }
        TasteSample("s$i", ratingOf(community, i), null, now - i * 5 * day, feature("s$i", tags, community))
    }

    "社区分映射：站点中位为中性 50，单调且落在 [5,95]" {
        TasteScoringParams.communityTargetScore(TasteScoringParams.COMMUNITY_NEUTRAL_SCORE) shouldBe
            (50.0 plusOrMinus 1e-9)
        var previous = 0.0
        for (i in 10..100) {
            val v = TasteScoringParams.communityTargetScore(i / 10.0)
            (v >= previous) shouldBe true
            (v in 5.0..95.0) shouldBe true
            previous = v
        }
        TasteScoringParams.communityTargetScore(8.5) shouldBeGreaterThan 85.0
        (TasteScoringParams.communityTargetScore(6.0) < 40.0) shouldBe true
    }

    "门控融合：画像有强立场时社区分几乎不动它，画像中性时社区分补位" {
        TasteScoringParams.communityBlendedScore(92, 5.0, 20000) shouldBeInRange 85..92
        TasteScoringParams.communityBlendedScore(8, 9.0, 20000) shouldBeInRange 8..15
        TasteScoringParams.communityBlendedScore(50, 8.6, 20000) shouldBeGreaterThan 75
        TasteScoringParams.communityBlendedScore(50, null, 0) shouldBe 50
    }

    "预计满意度：社区分与画像同向时更极端，画像权重随评分数增加" {
        fun sat(z: Double?, c: Double?, n: Int = 60, votes: Int = 5000, evidence: Double = 1.0) =
            TasteScoringParams.satisfactionScore(z, evidence, c, votes, n)
        // 中性：站点中位口碑 + 画像无立场 → 50。
        sat(0.0, TasteScoringParams.COMMUNITY_NEUTRAL_SCORE) shouldBe 50
        // 单调：口碑越好 / 画像越契合，满意度越高。
        sat(0.0, 8.5) shouldBeGreaterThan sat(0.0, 7.0)
        sat(2.0, 7.5) shouldBeGreaterThan sat(0.0, 7.5)
        sat(0.0, 7.5) shouldBeGreaterThan sat(-2.0, 7.5)
        // 口碑极好的陌生题材：满意度仍然高（这正是它与匹配度的区别）。
        sat(-1.0, 8.8) shouldBeGreaterThan 70
        // 评分多的用户，画像话语权更大。
        (sat(3.0, 7.0, n = 100) - sat(0.0, 7.0, n = 100)) shouldBeGreaterThan
            (sat(3.0, 7.0, n = 10) - sat(0.0, 7.0, n = 10))
        // 十几个人打出的极端分被收缩。
        sat(0.0, 9.5, votes = 8) shouldBeLessThan sat(0.0, 9.5, votes = 5000)
        // 没有证据的画像分不参与；什么都没有时为中性。
        sat(3.0, 7.0, evidence = 0.0) shouldBe sat(0.0, 7.0)
        sat(null, null) shouldBe 50
        for (c in listOf(1.0, 5.0, 9.9)) sat(3.0, c) shouldBeInRange 5..95
    }

    "端到端：匹配度由口味主导，满意度由口碑托底" {
        val profile = build(samples(40) { community, i -> if (i % 2 == 0) 9 else community.toInt() }, now)
        val strangerTags = listOf("纪实" to 30, "职人" to 20, "历史" to 10)
        val acclaimed = compute(feature("x", strangerTags, 8.9f, 20000), profile)
        val mediocre = compute(feature("y", strangerTags, 6.0f, 20000), profile)
        // 同样陌生的题材：口碑好的那部，预计评分与满意度都明显更高。
        (acclaimed.satisfaction - mediocre.satisfaction) shouldBeGreaterThan 10
        (acclaimed.prediction!!.rating > mediocre.prediction!!.rating + 0.8) shouldBe true
        val rated = compute(feature("s0", listOf("奇幻" to 30), 6.0f), profile, userRating = 10)
        rated.satisfaction shouldBe rated.score
    }

    "证据量收缩：没有标签的作品回到中性附近，而不是被判成反口味" {
        val profile = build(samples(40) { community, i -> if (i % 2 == 0) 9 else (community).toInt() }, now)
        val untagged = compute(feature("untagged", emptyList(), score = null, votes = 0), profile)
        untagged.score shouldBeInRange 45..55
        untagged.evidence shouldBe 0.0
        // 同一画像下，标签齐全的作品证据量为 1，置信度更高。
        val tagged = compute(feature("tagged", listOf("奇幻" to 30, "冒险" to 20, "战斗" to 10), null, 0), profile)
        tagged.evidence shouldBe 1.0
        tagged.confidence shouldBeGreaterThan untagged.confidence
    }

    "证据量：单个标签正好命中画像明确反感的题材时不收缩" {
        TasteScoringParams.candidateEvidence(contentTagCount = 1, familiarity = 1.0) shouldBe 1.0
        TasteScoringParams.candidateEvidence(contentTagCount = 1, familiarity = 0.0) shouldBe 0.5
        TasteScoringParams.candidateEvidence(contentTagCount = 0, familiarity = 0.0) shouldBe 0.0
        TasteScoringParams.candidateEvidence(contentTagCount = 12, familiarity = 0.2) shouldBe 1.0
    }

    "正向理由不包含负贡献项" {
        val profile = build(
            samples(40) { _, i -> if (i % 2 == 0) 9 else 3 }.map { s ->
                // 偶数样本（高分）全是奇幻，奇数样本（低分）全是恋爱 → 画像对两者立场明确。
                val tag = if (s.rating!! >= 9) "奇幻" else "恋爱"
                s.copy(feature = feature(s.subjectId, listOf(tag to 40, "冒险" to 5), 7.0f))
            },
            now,
        )
        val mixed = compute(feature("mixed", listOf("奇幻" to 30, "恋爱" to 30), 7.0f), profile)
        mixed.positiveReasons.all { it.contribution > 0.0 } shouldBe true
    }

    "样本选择：强制样本超过上限时仍给中间分作品留出名额" {
        // 300 部里 250 部是 9–10 分（全部属于强制样本），50 部是 6 分的「恋爱」。
        val heavy = (0 until 300).map { i ->
            val high = i < 250
            TasteSample(
                subjectId = "h$i",
                rating = if (high) 9 + i % 2 else 6,
                comment = null,
                updatedAtMillis = now - i * day,
                feature = feature("h$i", listOf((if (high) "奇幻" else "恋爱") to 40, "标签$i" to 5), 7.0f),
            )
        }
        val profile = build(heavy, now)
        profile.sampleCount shouldBe TasteScoringParams.MAX_PROFILE_SAMPLES
        // 中间分作品进了画像 → 「恋爱」作为相对不喜欢的题材出现在负向量里。
        (profile.category(TasteCategory.TOPIC).negative["恋爱"] ?: 0.0) shouldBeGreaterThan 0.0
        // 全部已评分作品仍保留显式评分锚。
        profile.ratedSubjectScores.size shouldBe 300
    }
})
