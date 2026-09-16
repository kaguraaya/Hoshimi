package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random

/** 主分数与预计评分必须同向：主分数是预计评分在用户自己打分历史里的位置。 */
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

    "the headline score follows the predicted rating, not tag similarity" {
        // 题材陌生（全是画像里没有的标签）但口碑很好：旧算法给低分，现在主分数跟预计评分走。
        val unfamiliarButGood = compute(feature("a", listOf("性喜剧", "学生会", "下品"), 8.4f), profile)
        val prediction = unfamiliarButGood.prediction
        prediction shouldNotBe null
        unfamiliarButGood.fromRatingModel shouldBe true
        unfamiliarButGood.score shouldBe model.likeability(prediction!!.rating)
        unfamiliarButGood.satisfaction shouldBe unfamiliarButGood.score

        // 预计评分更高的作品，主分数不会更低。
        val better = compute(feature("b", listOf("搞笑", "日常"), 8.6f), profile)
        val worse = compute(feature("c", listOf("搞笑", "日常"), 6.4f), profile)
        (better.prediction!!.rating > worse.prediction!!.rating) shouldBe true
        better.score shouldBeGreaterThan worse.score
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
        TasteVerdict.of(80, 8.2) shouldBe "很可能喜欢"
        TasteVerdict.of(55, 7.5) shouldBe "应该不错"
        // 评分太少、没有预计评分时，沿用画像匹配分的措辞，且不说「不太喜欢」。
        TasteVerdict.of(30, null) shouldBe "题材上不太对口"
    }
})
