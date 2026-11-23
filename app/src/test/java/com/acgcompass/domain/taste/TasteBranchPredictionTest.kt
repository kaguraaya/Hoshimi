package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random

/**
 * 预计评分接入分支：保守 / 激进调个人偏离的倍数和离你平均分距离的倍数（常规不变），匹配度的部件仍用常规预测；
 * 回测给刚评分的作品先算评分前的盲测值、改了分的作品沿用旧值；画像还没重建时当前的分就是盲测值。
 */
class TasteBranchPredictionTest : StringSpec({
    val random = Random(5)
    val tags = listOf("日常", "校园", "悬疑", "科幻", "治愈", "运动", "战斗", "恋爱")
    fun feature(id: String, lead: String) = WorkFeature(
        subjectId = id,
        tagCounts = (listOf(lead) + tags.shuffled(random).take(3)).distinct()
            .mapIndexed { k, t -> TagCount(t, 120 - k * 25) },
        bangumiScore = 6.5f + random.nextInt(20) / 10f,
        bangumiVotes = 500 + random.nextInt(5000),
    )
    // 喜欢治愈、不喜欢战斗：个人偏离明显，分支倍数才看得出差别。
    val samples = (0 until 60).map { i ->
        val lead = tags[i % tags.size]
        val f = feature("s$i", lead)
        val c = f.bangumiScore!!.toDouble()
        val rating = when (lead) {
            "治愈" -> c + 2.0
            "战斗" -> c - 2.0
            else -> c
        }.toInt().coerceIn(1, 10)
        TasteSample("s$i", rating, null, 1_000L * i, f)
    }
    val pool = (0 until 80).map { feature("p$it", tags[it % tags.size]) }
    val profile = BuildTasteProfileUseCase()(samples, 100_000L, pool, divergenceOptions = DivergenceOptions.DEFAULT)
    val model = profile.divergence!!
    val healing = feature("new-healing", "治愈")

    "scale 1 is the standard prediction; other scales stretch the personal part around the community baseline" {
        val standard = model.predict(healing)
        model.predict(healing, 1.0).rating shouldBe standard.rating
        val loc = standard.location!!
        val base = standard.baseline
        (loc - base) shouldNotBe 0.0
        model.predict(healing, 0.7).location!! shouldBe (base + 0.7 * (loc - base) plusOrMinus 1e-9)
        model.predict(healing, 1.3).location!! shouldBe (base + 1.3 * (loc - base) plusOrMinus 1e-9)
    }

    "spread stretches the location around your mean rating" {
        val standard = model.predict(healing)
        val loc = standard.location!!
        val mean = model.meanRating
        model.predict(healing, 1.0, 0.7).location!! shouldBe (mean + 0.7 * (loc - mean) plusOrMinus 1e-9)
        model.predict(healing, 1.0, 1.3).location!! shouldBe (mean + 1.3 * (loc - mean) plusOrMinus 1e-9)
        val wide = model.predict(healing, 1.0, 1.3)
        // 位置离平均分越远，分布越往那一头偏：高于平均分时 9 分以上的可能变大。
        if (loc > mean) (wide.highChance!! > standard.highChance!!) shouldBe true
    }

    "switching branch changes the predicted rating but not the match components" {
        val compute = ComputeTasteMatchUseCase()
        val results = TasteMatchBranch.entries.associateWith { b ->
            compute(healing, profile.copy(matchBranch = b), withNeighbors = false, withReasons = false)
        }
        val standard = results.getValue(TasteMatchBranch.STANDARD).prediction!!
        val mean = model.meanRating
        for (b in listOf(TasteMatchBranch.CONSERVATIVE, TasteMatchBranch.AGGRESSIVE)) {
            val p = results.getValue(b).prediction!!
            val personal = standard.baseline + b.ratingScale * (standard.location!! - standard.baseline)
            p.location!! shouldBe (mean + b.ratingSpread * (personal - mean) plusOrMinus 1e-9)
            p.branch shouldBe b
            p.branchOffset shouldBe (p.rating - standard.rating plusOrMinus 1e-12)
            // 归因按常规列出，分支带来的变化单独一项。
            p.factors shouldBe standard.factors
        }
        standard.branch shouldBe null
        standard.branchOffset shouldBe 0.0
        val parts = results.values.map { TasteFit.components(it)!!.toList() }
        parts.forEach { it shouldContainExactly parts.first() }
    }

    "before the profile is rebuilt, the current result of a newly rated work is already blind" {
        val compute = ComputeTasteMatchUseCase()
        val match = compute(healing, profile, userRating = 9, withNeighbors = false, withReasons = false)
        val view = TasteBacktest.beforeRating("new-healing", match, 9, profile).shouldNotBeNull()
        view.rating shouldBe 9
        view.updating shouldBe true
        view.prediction shouldBe match.prediction
        if (profile.tasteFit != null) view.match shouldBe match.score
        // 画像已经知道评分的作品不是盲测。
        val known = compute(samples[1].feature, profile, withNeighbors = false, withReasons = false)
        TasteBacktest.beforeRating("s1", known, 7, profile) shouldBe null
        TasteBacktest.beforeRating("new-healing", match, null, profile) shouldBe null
    }

    "a newly rated work gets a blind value from the profile built before the rating" {
        val rated = TasteSample("new-healing", 9, null, 200_000L, healing)
        val provisional = TasteBacktest.provisional(profile, samples + rated, known = emptySet())
        provisional shouldContainKey "new-healing"
        provisional.getValue("new-healing").rating shouldBe 9
        // 已在旧画像里有评分的作品不算（不是盲测），已有回测值的也不重复算。
        TasteBacktest.provisional(profile, samples, known = emptySet()) shouldNotContainKey "s1"
        TasteBacktest.provisional(profile, samples + rated, known = setOf("new-healing")) shouldNotContainKey
            "new-healing"
    }

    "the folds holding the priority works come first" {
        val inputs = TasteBuildInputs(samples, 100_000L, pool, emptyMap(), emptyList(), DivergenceOptions.DEFAULT)
        val firstFold = mutableListOf<Set<String>>()
        TasteBacktest.run(inputs, priority = setOf("s7"), onFold = { firstFold += it.keys })
        firstFold.first().contains("s7") shouldBe true
    }
})
