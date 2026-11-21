package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe

/** 匹配度刻度锚在「作品库」和「你看过的作品」上：单调、锚点落在约定的分数上、看过的作品太少时也给出合理刻度。 */
class TasteFitAnchorsTest : StringSpec({
    val pool = (0 until 200).map { it / 100.0 - 1.0 }.sorted() // -1 … 1
    val watched = (0 until 40).map { 0.8 + it / 40.0 }.sorted() // 0.8 … 1.8

    "anchors map the pool median, your median and your top 10% to the branch scale" {
        val scale = TasteFit.weights(TasteMatchBranch.STANDARD).scale
        val a = Anchors.of(pool, watched)
        a.map(a.poolMid, scale) shouldBe scale.mid.toInt()
        a.map(a.watchedMid, scale) shouldBe scale.watched.toInt()
        a.map(a.watchedTop, scale) shouldBe scale.top.toInt()
        a.map(a.poolLow, scale) shouldBe scale.low.toInt()
    }

    "the mapping is monotone and stays within 5 to 99" {
        val scale = TasteFit.weights(TasteMatchBranch.AGGRESSIVE).scale
        val a = Anchors.of(pool, watched)
        val scores = (-60..60).map { a.map(it / 10.0, scale) }
        scores.zipWithNext().all { (x, y) -> y >= x } shouldBe true
        scores.first() shouldBeInRange 5..99
        scores.last() shouldBeInRange 5..99
    }

    "with too few watched works the anchors still sit above the pool median" {
        val a = Anchors.of(pool, listOf(0.1, 0.2))
        (a.watchedMid > a.poolMid) shouldBe true
        (a.watchedTop > a.watchedMid) shouldBe true
    }

    "branches differ mostly at the ends, not by a constant shift" {
        val a = Anchors.of(pool, watched)
        fun at(branch: TasteMatchBranch, x: Double) = a.map(x, TasteFit.weights(branch).scale)
        val low = at(TasteMatchBranch.CONSERVATIVE, -0.9) - at(TasteMatchBranch.AGGRESSIVE, -0.9)
        val high = at(TasteMatchBranch.AGGRESSIVE, a.watchedTop) - at(TasteMatchBranch.CONSERVATIVE, a.watchedTop)
        low shouldBeGreaterThanOrEqual 5
        high shouldBeGreaterThanOrEqual 5
    }
})
