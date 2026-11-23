package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * 画像重建、回测重算期间显示什么：继续用上一次的回测值并标「正在更新」（改了分的作品也一样，留出算的值和它自己的评分无关）；
 * 完全没有回测值时标成待定，不退回把这部作品自己的评分算进去的分。
 */
class TasteBacktestPendingTest : StringSpec({
    fun entry(id: String, rating: Int) = BacktestEntry(id, rating, components = null, scale = null, prediction = null)
    val done = TasteBacktest(mapOf("a" to entry("a", 8), "b" to entry("b", 6)))

    "a finished backtest has a value for every rated work it covers" {
        done.view("a", TasteMatchBranch.STANDARD, rating = 8).shouldNotBeNull().updating shouldBe false
        TasteBacktest.pending(done, "a", 8) shouldBe false
    }

    "while recomputing, the previous result stays and is marked as updating" {
        val updating = done.updatingCopy()
        updating.view("a", TasteMatchBranch.STANDARD, rating = 8).shouldNotBeNull().updating shouldBe true
        TasteBacktest.pending(updating, "a", 8) shouldBe false
    }

    "a work rated after the last backtest is pending until the new one finishes" {
        TasteBacktest.pending(done.updatingCopy(), "new", 9) shouldBe true
        TasteBacktest.pending(null, "a", 8) shouldBe true
    }

    "a changed rating keeps the old blind value, shows the new rating and is marked as updating" {
        val view = done.view("b", TasteMatchBranch.STANDARD, rating = 7).shouldNotBeNull()
        view.rating shouldBe 7
        view.updating shouldBe true
        TasteBacktest.pending(done, "b", 7) shouldBe false
        TasteBacktest.pending(done.updatingCopy(), "b", 7) shouldBe false
    }

    "works the backtest never covers are not pending forever" {
        TasteBacktest.pending(done, "missing", 7) shouldBe false
        TasteBacktest.pending(TasteBacktest(emptyMap()), "a", 8) shouldBe false
    }
})
