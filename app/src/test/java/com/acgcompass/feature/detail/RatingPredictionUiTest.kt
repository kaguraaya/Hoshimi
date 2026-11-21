package com.acgcompass.feature.detail

import com.acgcompass.domain.taste.RatingPrediction
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class RatingPredictionUiTest : StringSpec({
    fun prediction(low: Double?, high: Double?) = RatingPrediction(
        rating = 7.8,
        baseline = 7.4,
        divergence = 0.4,
        factors = emptyList(),
        community = 7.6,
        userMean = 7.0,
        userStd = 1.2,
        low = low,
        high = high,
    )

    "the prediction block shows the interval when the model gives one" {
        prediction(6.84, 8.91).toUi().intervalText shouldBe "大概率在 6.8–8.9 分之间"
    }

    "no interval line for the fixed-weight model" {
        prediction(null, null).toUi().intervalText.shouldBeNull()
    }
})
