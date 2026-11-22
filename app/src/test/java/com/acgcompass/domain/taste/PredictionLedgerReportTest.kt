package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PredictionLedgerReportTest : StringSpec({
    fun entry(id: String, version: String, predicted: Double?, actual: Int, interval: Pair<Double, Double>? = null) =
        LedgerEntry(
            id, version, PredictionContext.DETAIL, predicted, interval?.first, interval?.second, 60, actual, 100,
        )

    "per-version error, interval coverage and rank agreement" {
        val rows = listOf(
            entry("1", "gate3", 8.0, 9, 7.0 to 9.5),
            entry("2", "gate3", 6.0, 5, 5.5 to 7.0),
            entry("3", "gate3", 7.0, 7, 6.0 to 8.0),
            entry("4", "gate3", 9.0, 10, 8.0 to 9.5),
            entry("5", "gate3", 5.0, 4, 4.5 to 6.0),
            entry("6", "gate3", null, 8),
        )

        val stats = PredictionLedgerReport.byVersion(rows).single()

        stats.count shouldBe 5
        stats.mae!! shouldBe (0.8 plusOrMinus 1e-9)
        stats.intervalCoverage!! shouldBe (0.4 plusOrMinus 1e-9) // 9 ∈ [7,9.5]、7 ∈ [6,8]
        stats.spearman!! shouldBe (1.0 plusOrMinus 1e-9)
    }

    "a version without intervals reports no coverage" {
        val stats = PredictionLedgerReport.byVersion(listOf(entry("1", "legacy1", 7.0, 8))).single()

        stats.intervalCoverage.shouldBeNull()
        stats.spearman.shouldBeNull()
    }

    "paired comparison only uses works both versions predicted" {
        val rows = listOf(
            entry("1", "legacy1", 6.0, 8),
            entry("1", "gate3", 7.5, 8),
            entry("2", "legacy1", 7.0, 7),
            entry("2", "gate3", 7.5, 7),
            entry("3", "gate3", 9.0, 2),
        )

        val paired = PredictionLedgerReport.paired(rows, "legacy1", "gate3")

        paired.count shouldBe 2
        paired.maeDiff!! shouldBe (-0.5 plusOrMinus 1e-9) // (0.5−2 + 0.5−0) / 2
        paired.otherBetterShare!! shouldBe (0.5 plusOrMinus 1e-9)
    }

    "model version names follow the options" {
        TasteModelVersion.of(DivergenceOptions.LEGACY) shouldBe "legacy1"
        TasteModelVersion.of(DivergenceOptions(perUserGate = true, scale = ScaleMode.QUANTILE)) shouldBe "gate3-q"
    }
})
