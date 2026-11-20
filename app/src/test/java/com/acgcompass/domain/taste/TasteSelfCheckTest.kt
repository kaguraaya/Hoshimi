package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class TasteSelfCheckTest : StringSpec({
    val tags = listOf("日常", "校园", "悬疑", "后宫", "科幻", "运动")

    fun samples(n: Int): List<TasteSample> {
        val r = Random(11)
        return (0 until n).map { i ->
            val community = 6.0f + r.nextInt(25) / 10f
            val chosen = tags.shuffled(r).take(3)
            val bias = if ("悬疑" in chosen) 1 else 0
            TasteSample(
                "s$i",
                (community + bias).toInt().coerceIn(1, 10),
                null,
                1_000L * i,
                WorkFeature(
                    subjectId = "s$i",
                    tagCounts = chosen.mapIndexed { k, t -> TagCount(t, 60 - k * 10) },
                    bangumiScore = community,
                    bangumiVotes = if (i % 10 == 0) 20 else 3000,
                ),
            )
        }
    }

    "spearman handles ties and perfect orderings" {
        TasteSelfCheck.spearman(listOf(1.0, 2.0, 3.0), listOf(10.0, 20.0, 30.0)) shouldBe (1.0 plusOrMinus 1e-9)
        TasteSelfCheck.spearman(listOf(1.0, 2.0, 3.0), listOf(3.0, 2.0, 1.0)) shouldBe (-1.0 plusOrMinus 1e-9)
        TasteSelfCheck.spearman(listOf(1.0, 1.0), listOf(1.0, 2.0)) shouldBe 0.0
    }

    "too few ratings give data quality but no accuracy" {
        val report = TasteSelfCheck.run(samples(5), emptyList(), now = 1L)
        report.accuracy.shouldBeNull()
        report.quality.rated shouldBe 5
    }

    "k-fold evaluation predicts held-out works better than chance and reports data quality" {
        val report = TasteSelfCheck.run(samples(80), emptyList(), now = 1L)
        val accuracy = report.accuracy.shouldNotBeNull()
        accuracy.predicted shouldBe 80
        accuracy.spearman shouldBeGreaterThan 0.5
        report.quality.tagCoverage shouldBe 1.0
        report.quality.lowVoteShare shouldBe (0.1 plusOrMinus 1e-9)
    }
})
