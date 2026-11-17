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

    "headline mapping is centred and bounded" {
        ComputeTasteMatchUseCase.headlineScore(0.0, 0.0, 50) shouldBe 70
        ComputeTasteMatchUseCase.headlineScore(0.4, 0.5, 80) shouldBeInRange 84..92
        // 低分端只按一半往下：同样幅度的负向证据，掉得比正向涨得少。
        (70 - ComputeTasteMatchUseCase.headlineScore(-1.0, -1.0, 30)) shouldBeLessThan
            (ComputeTasteMatchUseCase.headlineScore(1.0, 1.0, 70) - 70)
        ComputeTasteMatchUseCase.headlineScore(3.0, 3.0, 95) shouldBe 97
    }

    "the personal scale spreads scores around the local pool" {
        val r = Random(5)
        val pool = (0 until 80).map { i ->
            val tags = (if (i % 3 == 0) listOf("搞笑") else emptyList()) + neutral.shuffled(r).take(3)
            feature("p$i", tags, 6.0f + r.nextInt(25) / 10f)
        }
        val calibration = HeadlineCalibrator.fit(profile, pool, compute)
        calibration shouldNotBe null
        val c = calibration!!
        (c.spread >= 0.25) shouldBe true
        ComputeTasteMatchUseCase.calibratedScore(c.center, c) shouldBe 72
        ComputeTasteMatchUseCase.calibratedScore(c.center + 1.3 * c.spread, c) shouldBeInRange 86..90
        ComputeTasteMatchUseCase.calibratedScore(c.center - 1.3 * c.spread, c) shouldBeInRange 56..60

        // 用个人刻度后，同一批作品的分数比固定刻度拉得更开，排序不变。
        val calibrated = profile.copy(headlineCalibration = c)
        val scores = pool.take(40).map { compute(it, calibrated).score }
        (scores.max() - scores.min()) shouldBeGreaterThan 15
    }

    "the bold style looks only at similar works you rated" {
        ComputeTasteMatchUseCase.headlineZ(1.5, 0.3, 90, bold = true) shouldBe 0.3
        // 找不到相似作品时仍按综合口径。
        ComputeTasteMatchUseCase.headlineZ(1.0, null, 50, bold = true) shouldBe
            ComputeTasteMatchUseCase.headlineZ(1.0, null, 50)
        val bold = profile.copy(boldMatch = true)
        val comedy = compute(feature("g", listOf("搞笑", "日常", "校园"), 6.6f), bold)
        comedy.headlineZ shouldBe comedy.analogy?.z
    }

    "too small a pool falls back to the fixed scale" {
        HeadlineCalibrator.fit(profile, emptyList(), compute) shouldBe null
    }
})
