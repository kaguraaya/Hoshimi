package com.acgcompass.feature.detail

import com.acgcompass.domain.taste.BacktestView
import com.acgcompass.domain.taste.TasteMatchResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/** 已评分作品的匹配度：回测值、正在更新的上一次回测值、回测还没算完时的占位。 */
class BacktestDisplayTest : StringSpec({
    fun rated(backtest: BacktestView?, pending: Boolean) = TasteMatchResult(
        score = 93,
        confidence = 0.9,
        rawZ = 1.0,
        probability = 0.9,
        isRated = true,
        fromRatingModel = true,
        backtest = backtest,
        backtestPending = pending,
    )

    "shows the backtest match, not the score that already knows your rating" {
        val ui = rated(BacktestView(78, 7.6, 6.0, 9.0, rating = 8), pending = false).toTasteMatchUi()
        ui.shouldBeInstanceOf<TasteMatchUiModel.Available>()
        ui.percentText shouldBe "78%"
    }

    "while recomputing, the previous backtest is shown and labelled" {
        val ui = rated(BacktestView(78, 7.6, 6.0, 9.0, rating = 8, updating = true), pending = false).toTasteMatchUi()
        ui.shouldBeInstanceOf<TasteMatchUiModel.Available>()
        ui.percentText shouldBe "78%"
        ui.backtestText!! shouldContain "上一次的结果"
    }

    "without a backtest yet, no number is shown" {
        val ui = rated(null, pending = true).toTasteMatchUi()
        ui.shouldBeInstanceOf<TasteMatchUiModel.Available>()
        ui.percentText shouldBe "回测中"
        ui.fraction shouldBe 0f
        ui.backtestText!! shouldContain "回测计算中"
    }
})
