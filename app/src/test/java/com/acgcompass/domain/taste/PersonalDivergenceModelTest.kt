package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random

/**
 * 个人分歧模型守卫。造一个「规律已知」的用户：评分 = 社区分 + 题材偏好 + 噪声，
 * 检验模型能不能把埋进去的偏好找出来，并在新作品上给出方向正确的预测与归因。
 */
class PersonalDivergenceModelTest : StringSpec({

    val neutral = listOf("日常", "校园", "冒险", "奇幻", "科幻", "音乐", "运动", "历史")

    @Suppress("LongParameterList")
    fun feature(
        id: String,
        tags: List<String>,
        community: Float,
        votes: Int = 3000,
        eps: Int = 12,
        title: String = id,
    ) = WorkFeature(
        subjectId = id,
        tagCounts = tags.mapIndexed { i, t -> TagCount(t, 100 - i * 10) } + TagCount("2015年4月", 40),
        bangumiScore = community,
        bangumiVotes = votes,
        eps = eps,
        titleVariants = listOf(title),
    )

    /** 这位用户：带「悬疑」的作品比大众多给 2 分，带「后宫」的少给 2 分，其余跟着社区走。 */
    fun samples(n: Int): List<TasteSample> {
        val r = Random(7)
        return (0 until n).map { i ->
            val community = 6.0f + r.nextInt(25) / 10f
            val extra = when (i % 4) {
                0 -> "悬疑"
                1 -> "后宫"
                else -> null
            }
            val tags = listOfNotNull(extra) + neutral.shuffled(r).take(3)
            val bias = when (extra) {
                "悬疑" -> 2
                "后宫" -> -2
                else -> 0
            }
            val rating = (community + bias).toInt().coerceIn(1, 10)
            TasteSample("s$i", rating, null, 1_000L * i, feature("s$i", tags, community))
        }
    }

    val model = PersonalDivergenceModel.fit(samples(80))!!

    "样本不足或没有社区分时不建模" {
        PersonalDivergenceModel.fit(samples(5)) shouldBe null
        val unscored = samples(40).map { it.copy(feature = it.feature.copy(bangumiScore = null)) }
        PersonalDivergenceModel.fit(unscored) shouldBe null
    }

    "找回埋进去的题材分歧：悬疑为正、后宫为负，且出现在「分歧最大的标签」里" {
        val (liked, disliked) = model.strongestTags(3)
        liked.first().label shouldBe "悬疑"
        disliked.first().label shouldBe "后宫"
        liked.first().delta shouldBeGreaterThan 0.5
        disliked.first().delta shouldBeLessThan -0.5
    }

    "新作品的预测：方向正确，并把原因归到对应标签" {
        val mystery = model.predict(feature("m", listOf("悬疑", "日常", "校园"), 7.0f))
        val harem = model.predict(feature("h", listOf("后宫", "日常", "校园"), 7.0f))
        val plain = model.predict(feature("p", listOf("日常", "校园", "音乐"), 7.0f))
        mystery.divergence shouldBeGreaterThan 0.8
        harem.divergence shouldBeLessThan -0.8
        (kotlin.math.abs(plain.divergence) < 0.6) shouldBe true
        mystery.rating shouldBeGreaterThan plain.rating
        plain.rating shouldBeGreaterThan harem.rating
        mystery.factors.first().label shouldBe "悬疑"
        harem.factors.first().label shouldBe "后宫"
        // 预计评分 = 大众基线 + 个人分歧（未触及 1–10 边界时）。
        mystery.rating shouldBe (mystery.baseline + mystery.divergence plusOrMinus 1e-9)
    }

    "大众基线随社区分单调，且落在 1–10" {
        val low = model.predict(feature("l", listOf("日常"), 5.0f))
        val high = model.predict(feature("g", listOf("日常"), 9.0f))
        high.baseline shouldBeGreaterThan low.baseline
        model.predict(feature("x", listOf("后宫"), 1.5f)).rating shouldBeGreaterThan 0.99
        model.predict(feature("y", listOf("悬疑"), 9.9f)).rating shouldBeLessThan 10.01
        // 没有社区分的作品：基线取你的均分。
        model.baseline(null) shouldBe model.meanRating
    }

    "系列一致性：同系列前作被你明显高估时，续作的预测上调" {
        val base = samples(60)
        val season1 = TasteSample(
            "saga1",
            10,
            null,
            99_000L,
            feature("saga1", listOf("日常", "校园", "音乐"), 6.5f, title = "某某物语"),
        )
        val withSaga = PersonalDivergenceModel.fit(base + season1)!!
        val season2 = feature("saga2", listOf("运动", "历史", "科幻"), 6.5f, title = "某某物语 第二季")
        val stranger = feature("other", listOf("运动", "历史", "科幻"), 6.5f, title = "别的作品")
        withSaga.predict(season2).divergence shouldBeGreaterThan withSaga.predict(stranger).divergence + 0.3
        withSaga.predict(season2).factors.any { it.label == PersonalDivergenceModel.FRANCHISE_LABEL } shouldBe true
    }

    "相似作品与最大分歧：返回的是你真正评过的作品" {
        val similar = model.nearestRated(feature("q", listOf("悬疑", "日常", "校园"), 7.0f), 5)
        similar shouldNotBe emptyList<RatedExample>()
        similar.all { it.subjectId.startsWith("s") } shouldBe true
        (similar.first().similarity >= similar.last().similarity) shouldBe true
        val (above, below) = model.strongestDivergences(3)
        above.all { it.divergence >= PersonalDivergenceModel.MIN_NOTABLE_DIVERGENCE } shouldBe true
        below.all { it.divergence <= -PersonalDivergenceModel.MIN_NOTABLE_DIVERGENCE } shouldBe true
    }

    "打分宽严：整体比社区高 1 分的用户 leniency ≈ +1" {
        val lenient = samples(60).map { it.copy(rating = (it.feature.bangumiScore!! + 1.5f).toInt().coerceIn(1, 10)) }
        PersonalDivergenceModel.fit(lenient)!!.leniency shouldBe (1.0 plusOrMinus 0.35)
    }

    "线性求解器：对称正定方程组解正确，非正定返回 null" {
        val x = LinearSolver.solveSymmetricPositiveDefinite(
            arrayOf(doubleArrayOf(4.0, 1.0), doubleArrayOf(1.0, 3.0)),
            doubleArrayOf(1.0, 2.0),
        )!!
        x[0] shouldBe (1.0 / 11.0 plusOrMinus 1e-9)
        x[1] shouldBe (7.0 / 11.0 plusOrMinus 1e-9)
        LinearSolver.solveSymmetricPositiveDefinite(
            arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 1.0)),
            doubleArrayOf(1.0, 1.0),
        ) shouldBe null
    }
})
