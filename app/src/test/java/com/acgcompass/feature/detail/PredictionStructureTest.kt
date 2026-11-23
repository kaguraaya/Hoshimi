package com.acgcompass.feature.detail

import com.acgcompass.domain.taste.RatingPrediction
import com.acgcompass.domain.taste.TasteMatchBranch
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** 「比社区评分高 / 低」那一行：打分习惯、个人口味，选了保守 / 激进时再单列分支带来的变化，各项加起来等于总差。 */
class PredictionStructureTest : StringSpec({
    fun prediction(branch: TasteMatchBranch?, offset: Double) = RatingPrediction(
        rating = 8.4,
        baseline = 7.7,
        divergence = 0.7,
        factors = emptyList(),
        community = 7.5,
        userMean = 7.2,
        userStd = 1.2,
        branch = branch,
        branchOffset = offset,
    )

    "the standard branch shows habit and taste only" {
        prediction(null, 0.0).toUi().communitySummary shouldBe "比社区评分高 0.9：打分习惯 +0.2，个人口味 +0.7"
    }

    "a bolder branch lists its own share, and the parts still add up" {
        prediction(TasteMatchBranch.AGGRESSIVE, 0.3).toUi().communitySummary shouldBe
            "比社区评分高 0.9：打分习惯 +0.2，个人口味 +0.4，激进算法 +0.3"
        prediction(TasteMatchBranch.CONSERVATIVE, -0.2).toUi().communitySummary shouldBe
            "比社区评分高 0.9：打分习惯 +0.2，个人口味 +0.9，保守算法 -0.2"
    }

    "a branch share is listed even when the total is close to the community score" {
        val p = prediction(TasteMatchBranch.CONSERVATIVE, -0.7).copy(rating = 7.5, divergence = -0.2)
        p.toUi().communitySummary shouldBe "和社区评分持平：打分习惯 +0.2，个人口味 +0.5，保守算法 -0.7"
        prediction(null, 0.0).copy(rating = 7.6, divergence = -0.1).toUi().communitySummary shouldBe "和社区评分基本一致"
    }

    "a branch share under 0.1 is left out" {
        prediction(TasteMatchBranch.AGGRESSIVE, 0.02).toUi().communitySummary shouldBe
            "比社区评分高 0.9：打分习惯 +0.2，个人口味 +0.7"
    }
})
