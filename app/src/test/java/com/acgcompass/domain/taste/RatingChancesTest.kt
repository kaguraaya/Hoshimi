package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/** 打各个分数的可能：累积可能单调、拆成各档后加起来为 1、预计评分越高越可能打高分。 */
class RatingChancesTest : StringSpec({
    val ratings = doubleArrayOf(5.0, 6.0, 6.0, 7.0, 7.0, 7.0, 8.0, 8.0, 8.0, 8.0, 9.0, 9.0, 10.0)
    val mean = ratings.average()
    val std = kotlin.math.sqrt(ratings.sumOf { (it - mean) * (it - mean) } / ratings.size)

    "cumulative chances never increase with the threshold" {
        for (rating in listOf(4.0, 6.5, 7.8, 8.6, 9.6)) {
            val c = RatingChances.cumulative(rating, mean, std, ratings, width = 2.3)
            c.zipWithNext().all { (a, b) -> a >= b } shouldBe true
        }
    }

    "buckets add up to one" {
        val c = RatingChances.cumulative(8.8, mean, std, ratings, width = 2.2)
        RatingChances.buckets(c).sumOf { it.second } shouldBe (1.0 plusOrMinus 1e-9)
        RatingChances.buckets(c).all { it.second >= 0.0 } shouldBe true
    }

    "a higher predicted rating makes nine or above more likely" {
        val low = RatingChances.nineOrAbove(RatingChances.cumulative(7.0, mean, std, ratings, 2.3))
        val high = RatingChances.nineOrAbove(RatingChances.cumulative(8.8, mean, std, ratings, 2.3))
        high shouldBeGreaterThan low
    }

    "the nine-or-above threshold matches the single logistic used before" {
        // σ(−0.946 + 2.058·位置 + 0.863·log 几率 − 0.071·区间宽)：分档模型的 ≥9 一档系数几乎相同。
        val position = (8.5 - mean) / std.coerceAtLeast(0.5)
        val high = ratings.count { it >= 9.0 }
        val odds = kotlin.math.ln((high + 0.5) / (ratings.size - high + 0.5))
        val logit = -0.946 + 2.058 * position + 0.863 * odds - 0.071 * 2.3
        val single = 1.0 / (1.0 + kotlin.math.exp(-logit))
        val nine = RatingChances.nineOrAbove(RatingChances.cumulative(8.5, mean, std, ratings, 2.3))
        nine shouldBe (single plusOrMinus 0.01)
    }

    "the summary comes from the same distribution as the bars" {
        // 一部打 9 分以上的可能约 56% 的作品：中位数和最可能都是 9，平均约 8.5，区间 7–10。
        val atLeast = listOf(0.9939, 0.9671, 0.8517, 0.5572, 0.1304)
        val s = RatingChances.summarize(atLeast, lowMean = 4.0)
        s.median shouldBe 9
        s.mode shouldBe 9
        s.modeChance shouldBe (0.4268 plusOrMinus 1e-4)
        s.mean shouldBe (8.495 plusOrMinus 0.01)
        s.low shouldBe 7
        s.high shouldBe 10
    }

    "the mean always stays within what the distribution allows" {
        for (rating in listOf(4.0, 6.5, 7.8, 8.6, 9.6)) {
            val c = RatingChances.cumulative(rating, mean, std, ratings, width = 2.3)
            val s = RatingChances.summarize(c, lowMean = 4.0)
            val buckets = RatingChances.buckets(c)
            val upper = 5.0 * (1.0 - c.first()) + buckets.filter { !it.first.contains("及以下") }
                .sumOf { (label, p) -> label.removeSuffix(" 分").toDouble() * p } + 6.0 * (c[0] - c[1])
            (s.mean <= upper + 1e-9) shouldBe true
            (s.median in 5..10) shouldBe true
            (s.low <= s.median && s.median <= s.high) shouldBe true
        }
    }
})
