package com.acgcompass.domain.usecase

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagBucket
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.TasteTagStat
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * 个性化口味评分器回归（口味算法核心引擎）。
 *
 * 验证：高分标签命中拉升、低分标签下压、元数据标签弱化（题材加成）、无画像不可用、
 * 无标签退化为社区相对分、数据不足判定。纯函数，无 Android/IO 依赖。
 */
class PersonalTasteScorerTest : StringSpec({

    val scorer = PersonalTasteScorer()

    fun work(id: String, tags: List<String>): Work = Work(
        id = id,
        titles = Titles(canonical = id),
        mediaType = MediaType.ANIME,
        primarySource = SourceId.BANGUMI,
        tags = tags.map { Tag(TagCategory.CONTENT_TYPE, it) },
    )

    fun profile(
        high: List<Pair<String, Int>> = emptyList(),
        low: List<Pair<String, Int>> = emptyList(),
        avg: Float = 7f,
        confidence: Float = 0.8f,
    ): TasteProfile = TasteProfile(
        id = "p",
        strictness = 0.3f,
        avgScore = avg,
        highScoreRarity = 0.5f,
        confidence = confidence,
        generatedAt = 0L,
        tagStats = high.map { TasteTagStat(it.first, TagBucket.HIGH_SCORE, it.second) } +
            low.map { TasteTagStat(it.first, TagBucket.LOW_SCORE, it.second) },
    )

    "无口味画像 → 不可用且 basis=NO_PROFILE" {
        val s = scorer.score(work("a", listOf("热血")), profile = null, communityScore10 = 7f)
        s.available shouldBe false
        s.basis shouldBe TasteScore.Basis.NO_PROFILE
    }

    "命中高分标签 → 个人分高于基线且回填命中标签" {
        val p = profile(high = listOf("热血" to 5))
        val s = scorer.score(work("a", listOf("热血")), p, communityScore10 = 7f)
        s.available shouldBe true
        s.basis shouldBe TasteScore.Basis.TAG_OVERLAP
        s.matchedHighTags shouldBe listOf("热血")
        (s.personal > 0.55f) shouldBe true
    }

    "命中低分标签 → 个人分被明显下压到基线以下" {
        val p = profile(low = listOf("猎奇" to 5))
        val s = scorer.score(work("a", listOf("猎奇")), p, communityScore10 = 7f)
        s.matchedLowTags shouldBe listOf("猎奇")
        (s.personal < 0.30f) shouldBe true
    }

    "对比度扩展使强命中明显高于口味相悖作品" {
        val p = profile(high = listOf("科幻" to 6, "悬疑" to 5, "时间旅行" to 3), low = listOf("子供向" to 4))
        val loved = scorer.score(work("loved", listOf("科幻", "悬疑", "时间旅行")), p, communityScore10 = 9f)
        val meh = scorer.score(work("meh", listOf("子供向")), p, communityScore10 = 6f)
        // 喜欢的高分番（多个高分题材命中）应显著偏高；口味相悖的明显偏低。
        (loved.fraction > 0.8f) shouldBe true
        (meh.fraction < 0.4f) shouldBe true
        (loved.fraction - meh.fraction > 0.45f) shouldBe true
    }

    "元数据标签（年份）权重远低于题材标签 → 同次数命中题材分更高（题材加成/元数据弱化）" {
        val p = profile(high = listOf("热血" to 5, "2024年" to 5))
        val genre = scorer.score(work("g", listOf("热血")), p, communityScore10 = null)
        val meta = scorer.score(work("m", listOf("2024年")), p, communityScore10 = null)
        (genre.personal > meta.personal) shouldBe true
    }

    "命中多个高分标签 → 总体吻合度更高（组合优于单标签）" {
        val p = profile(high = listOf("热血" to 5, "战斗" to 5, "魔法" to 5))
        val one = scorer.score(work("one", listOf("热血")), p, communityScore10 = null)
        val three = scorer.score(work("three", listOf("热血", "战斗", "魔法")), p, communityScore10 = null)
        (three.personal > one.personal) shouldBe true
    }

    "作品无标签但有社区评分 → 退化为社区相对你均分（basis=COMMUNITY_FALLBACK）" {
        val p = profile(high = listOf("热血" to 5), avg = 7f)
        val s = scorer.score(work("a", emptyList()), p, communityScore10 = 9f)
        s.available shouldBe true
        s.basis shouldBe TasteScore.Basis.COMMUNITY_FALLBACK
        // 社区 9 高于均分 7 → 相对分应高于中性 0.5。
        (s.fraction > 0.5f) shouldBe true
    }

    "作品无标签且无社区评分 → 数据不足，不可用（basis=INSUFFICIENT）" {
        val p = profile(high = listOf("热血" to 5))
        val s = scorer.score(work("a", emptyList()), p, communityScore10 = null)
        s.available shouldBe false
        s.basis shouldBe TasteScore.Basis.INSUFFICIENT
    }

    "低置信画像 → 综合匹配度向中性 0.5 收缩（弱于高置信同等命中）" {
        val highConf = scorer.score(work("a", listOf("热血")), profile(high = listOf("热血" to 5), confidence = 0.9f), 8f)
        val lowConf = scorer.score(work("a", listOf("热血")), profile(high = listOf("热血" to 5), confidence = 0.1f), 8f)
        lowConf.lowConfidence shouldBe true
        // 同等命中下，低置信的综合分更靠近 0.5（被收缩）。
        (kotlin.math.abs(lowConf.fraction - 0.5f) < kotlin.math.abs(highConf.fraction - 0.5f)) shouldBe true
    }
})
