package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random

/** 口味匹配度以相似作品类比为主，并与预计评分保持同向。 */
class TasteVerdictCoherenceTest : StringSpec({

    val neutral = listOf("日常", "校园", "冒险", "奇幻", "科幻", "音乐", "运动", "历史")

    fun feature(id: String, tags: List<String>, community: Float) = WorkFeature(
        subjectId = id,
        tagCounts = tags.mapIndexed { i, t -> TagCount(t, 100 - i * 10) } + TagCount("2015年4月", 40),
        bangumiScore = community,
        bangumiVotes = 3000,
        eps = 12,
        titleVariants = listOf(id),
    )

    /** 打分偏宽的用户：大多 7–9 分，喜欢「搞笑」（比大众多给 1 分），6 分算及格。 */
    fun lenientSamples(n: Int): List<TasteSample> {
        val r = Random(11)
        return (0 until n).map { i ->
            val community = 6.2f + r.nextInt(20) / 10f
            val comedy = i % 3 == 0
            val tags = (if (comedy) listOf("搞笑") else emptyList()) + neutral.shuffled(r).take(3)
            val rating = (community + 0.8f + if (comedy) 1f else 0f).toInt().coerceIn(1, 10)
            TasteSample("s$i", rating, null, 1_000L * i, feature("s$i", tags, community))
        }
    }

    val samples = lenientSamples(90)
    val model = PersonalDivergenceModel.fit(samples)!!
    val profile = BuildTasteProfileUseCase()(samples, 1_000_000L)
    val compute = ComputeTasteMatchUseCase()

    "likeability is monotone, bounded and centered on the user's own scale" {
        val scores = (10..100 step 5).map { model.likeability(it / 10.0) }
        scores.zipWithNext().all { (a, b) -> b >= a } shouldBe true
        scores.first() shouldBeGreaterThanOrEqual PersonalDivergenceModel.LIKEABILITY_MIN
        scores.last() shouldBe PersonalDivergenceModel.LIKEABILITY_MAX
        // 预计分等于这位用户的均分时，落在中间一段；远高于均分时接近上限。
        model.likeability(model.meanRating) shouldBeInRange 30..70
        model.likeability(model.meanRating + 2.0) shouldBeGreaterThan 80
        model.likeability(model.meanRating - 2.5) shouldBeLessThan 20
    }

    "the headline score combines analogy, predicted rating and similarity" {
        val unfamiliarButGood = compute(feature("a", listOf("性喜剧", "学生会", "下品"), 8.4f), profile)
        val prediction = unfamiliarButGood.prediction
        prediction shouldNotBe null
        unfamiliarButGood.fromRatingModel shouldBe true
        val ratingZ = (prediction!!.rating - model.meanRating) / model.ratingStd.coerceAtLeast(0.6)
        unfamiliarButGood.score shouldBe ComputeTasteMatchUseCase.headlineScore(
            ratingZ,
            unfamiliarButGood.analogy?.z,
            unfamiliarButGood.tasteSimilarity,
        )
        unfamiliarButGood.satisfaction shouldBe model.likeability(prediction.rating)

        // 同样的题材，预计评分更高的作品主分数不会更低。
        val better = compute(feature("b", listOf("搞笑", "日常"), 8.6f), profile)
        val worse = compute(feature("c", listOf("搞笑", "日常"), 6.4f), profile)
        (better.prediction!!.rating > worse.prediction!!.rating) shouldBe true
        better.score shouldBeGreaterThan worse.score
    }

    "works like the ones you rated higher get a higher match" {
        // 这位用户给「搞笑」作品多打 1 分：同样口碑下，搞笑作品的类比分与匹配度更高。
        val comedy = compute(feature("e", listOf("搞笑", "日常", "校园"), 7.4f), profile)
        val other = compute(feature("f", listOf("历史", "音乐", "运动"), 7.4f), profile)
        (comedy.analogy!!.z > other.analogy!!.z) shouldBe true
        comedy.score shouldBeGreaterThan other.score
    }

    "a work predicted at or above the user's average is never called a poor match" {
        val likely = compute(feature("d", listOf("搞笑", "校园"), 7.6f), profile)
        val predicted = likely.prediction!!.rating
        (predicted >= model.meanRating - 0.3) shouldBe true
        val verdict = TasteVerdict.of(likely.score, predicted)
        (verdict == "可能不太合你口味" || verdict == "对你来说可能偏一般") shouldBe false
    }

    "below the pass line the verdict says so regardless of percentile" {
        TasteVerdict.of(60, 5.4) shouldBe "可能不太合你口味"
        TasteVerdict.of(84, 8.2) shouldBe "很可能喜欢"
        TasteVerdict.of(74, 7.5) shouldBe "应该不错"
        // 评分太少、没有预计评分时，沿用画像匹配分的措辞，且不说「不太喜欢」。
        TasteVerdict.of(30, null) shouldBe "题材上不太对口"
    }

    "headline mapping is centred, monotone and softly bounded" {
        ComputeTasteMatchUseCase.headlineScore(0.0, 0.0, 50) shouldBe 70
        ComputeTasteMatchUseCase.headlineScore(0.4, 0.5, 80) shouldBeInRange 84..92
        val scores = (-30..30).map { ComputeTasteMatchUseCase.headlineScore(it / 10.0, it / 10.0, 50) }
        scores.zipWithNext().all { (a, b) -> b >= a } shouldBe true
        // 顶端平滑接近上限，不会一大片挤在 97。
        ComputeTasteMatchUseCase.headlineScore(1.5, 1.5, 80) shouldBeLessThan 97
        ComputeTasteMatchUseCase.headlineScore(30.0, 30.0, 95) shouldBe 97
        ComputeTasteMatchUseCase.headlineScore(-30.0, -30.0, 5) shouldBe 5
    }

    "the three branches behave as described" {
        val config = TasteAlgorithmConfig.DEFAULT
        val standard = config.formula(TasteMatchBranch.STANDARD)
        val conservative = config.formula(TasteMatchBranch.CONSERVATIVE)
        val aggressive = config.formula(TasteMatchBranch.AGGRESSIVE)
        fun score(f: HeadlineFormula, rating: Double, analogy: Double) =
            ComputeTasteMatchUseCase.headlineScore(rating, analogy, 50, f)

        // 保守：只跟预计评分走，相似作品类比不影响。
        score(conservative, 0.5, 2.0) shouldBe score(conservative, 0.5, -2.0)
        (score(conservative, 0.8, 0.0) > score(conservative, 0.2, 0.0)) shouldBe true
        // 激进：相似作品打分高时往上推得多，打分低时只轻扣。
        val up = score(aggressive, 0.0, 1.0) - score(aggressive, 0.0, 0.0)
        val down = score(aggressive, 0.0, 0.0) - score(aggressive, 0.0, -1.0)
        (up > down) shouldBe true
        // 预计评分在你的平均分以上时，激进不会因为类比偏低就掉到很低（不会「反向」）。
        score(aggressive, 0.3, -1.0) shouldBeGreaterThan 65
        // 常规：类比为主，正反两边同样对待。
        (score(standard, 0.0, 1.0) - 70) shouldBeGreaterThan (70 - score(standard, 0.0, -1.0)) - 5
    }

    "the selected branch is used by the engine" {
        val conservative = profile.copy(
            matchBranch = TasteMatchBranch.CONSERVATIVE,
            headlineFormula = TasteAlgorithmConfig.DEFAULT.formula(TasteMatchBranch.CONSERVATIVE),
        )
        val work = feature("h", listOf("搞笑", "日常", "校园"), 7.2f)
        val result = compute(work, conservative)
        val m = conservative.divergence!!
        val ratingZ = (result.prediction!!.rating - m.meanRating) / m.ratingStd.coerceAtLeast(0.6)
        result.score shouldBe ComputeTasteMatchUseCase.headlineScore(
            ratingZ,
            result.analogy?.z,
            result.tasteSimilarity,
            conservative.headlineFormula,
        )
    }

    "score-only mode gives the same score without reasons" {
        val work = feature("i", listOf("搞笑", "校园", "音乐"), 7.0f)
        val full = compute(work, profile, withNeighbors = false)
        val light = compute(work, profile, withNeighbors = false, withReasons = false)
        light.score shouldBe full.score
        light.reasons.isEmpty() shouldBe true
    }
})
