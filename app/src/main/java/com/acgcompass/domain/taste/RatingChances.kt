package com.acgcompass.domain.taste

import kotlin.math.exp
import kotlin.math.ln

/**
 * 打各个分数的可能（分档的有序模型）：对每个门槛 k = 6..10 各一条逻辑回归
 *
 * ```
 * logit P(分 ≥ k) = a_k + b_k·位置 + c_k·log(你打 ≥k 分的几率) + d_k·区间宽
 * 位置 = (预计评分 − 你的均分) / max(你的标准差, 0.5)
 * ```
 *
 * 系数在 Bangumi15M 时间切分的一半用户上拟合，在另一半用户上检验（docs/algorithm-eval.md §7.4）：
 * 各门槛的校准误差 0.2%–2.4%，比「把预测区间换成正态分布」（1.8%–7.6%）准。
 * 累积可能逐门槛取较小值，保证 P(≥6) ≥ P(≥7) ≥ … ≥ P(10 分)。
 */
object RatingChances {
    /** 门槛（含）：6、7、8、9、10 分。 */
    @Suppress("MagicNumber")
    val THRESHOLDS: List<Int> = listOf(6, 7, 8, 9, 10)

    /** 每个门槛的（截距, 位置, 本人几率, 区间宽），逐个写出便于和评测报告对照。 */
    @Suppress("MagicNumber")
    private val COEFFICIENTS: List<DoubleArray> = listOf(
        doubleArrayOf(1.541, 1.378, 0.741, -0.269),
        doubleArrayOf(0.354, 1.693, 0.957, -0.126),
        doubleArrayOf(-0.284, 1.785, 0.878, -0.045),
        doubleArrayOf(-0.949, 2.057, 0.863, -0.069),
        doubleArrayOf(-0.845, 1.768, 0.881, -0.306),
    )

    private const val STD_FLOOR = 0.5
    private const val HALF = 0.5
    private const val WIDTH = 3

    /**
     * 累积可能 P(分 ≥ k)，k 依次为 [THRESHOLDS]。
     * [ratings] 是你已有的评分（用来算均分、标准差和打 ≥k 分的比例），[width] 是预测区间宽度。
     */
    fun cumulative(rating: Double, mean: Double, std: Double, ratings: DoubleArray, width: Double): List<Double> {
        val n = ratings.size
        val position = (rating - mean) / std.coerceAtLeast(STD_FLOOR)
        var previous = 1.0
        return THRESHOLDS.mapIndexed { i, k ->
            val atLeast = ratings.count { it >= k }
            val c = COEFFICIENTS[i]
            val logit = c[0] + c[1] * position + c[2] * ln((atLeast + HALF) / (n - atLeast + HALF)) + c[WIDTH] * width
            val p = (1.0 / (1.0 + exp(-logit))).coerceAtMost(previous)
            previous = p
            p
        }
    }

    /** 打 9 分以上的可能（从累积可能里取）。 */
    fun nineOrAbove(atLeast: List<Double>): Double = atLeast[THRESHOLDS.indexOf(NINE)]

    /** 由累积可能拆成「10 分 / 9 分 / 8 分 / 7 分 / 6 分及以下」各自的可能（加起来为 1）。 */
    fun buckets(atLeast: List<Double>): List<Pair<String, Double>> {
        val byThreshold = THRESHOLDS.zip(atLeast).toMap()
        val lowest = THRESHOLDS[1]
        val exact = (THRESHOLDS.last() downTo lowest).map { k ->
            "$k 分" to byThreshold.getValue(k) - (byThreshold[k + 1] ?: 0.0)
        }
        return exact + ("${lowest - 1} 分及以下" to 1.0 - byThreshold.getValue(lowest))
    }

    /**
     * 分布的统计量：平均分（「6 分以下」那一档按 [lowMean] 计，即你自己 5 分及以下评分的平均）、
     * 中位数（P(≥k) ≥ 50% 的最高分）、最可能的分数和它的可能、10%–90% 区间（整数，含两端）。
     * 预计评分、最可能的分数、打 9 分以上的可能和区间都从这一个分布推出，互相不会矛盾。
     */
    fun summarize(atLeast: List<Double>, lowMean: Double): Summary {
        val byThreshold = THRESHOLDS.zip(atLeast).toMap()
        val lowest = THRESHOLDS.first()
        // 各分数自己的可能：低于最低门槛的合成一档，记在 lowest − 1 上。
        val exact = linkedMapOf(lowest - 1 to 1.0 - byThreshold.getValue(lowest))
        THRESHOLDS.forEach { k -> exact[k] = byThreshold.getValue(k) - (byThreshold[k + 1] ?: 0.0) }
        val mean = exact.entries.sumOf { (k, p) -> (if (k < lowest) lowMean else k.toDouble()) * p }
        val median = THRESHOLDS.lastOrNull { byThreshold.getValue(it) >= HALF } ?: (lowest - 1)
        val mode = exact.maxByOrNull { it.value }!!
        return Summary(
            mean = mean,
            median = median,
            mode = mode.key,
            modeChance = mode.value,
            low = quantile(exact, LOW_Q),
            high = quantile(exact, HIGH_Q),
        )
    }

    private fun quantile(exact: Map<Int, Double>, q: Double): Int {
        var acc = 0.0
        for ((k, p) in exact) {
            acc += p
            if (acc >= q - EPS) return k
        }
        return exact.keys.last()
    }

    /** [summarize] 的结果；[mode] / [median] / [low] 为 5 时表示「5 分及以下」。 */
    data class Summary(
        val mean: Double,
        val median: Int,
        val mode: Int,
        val modeChance: Double,
        val low: Int,
        val high: Int,
    )

    private const val NINE = 9
    private const val LOW_Q = 0.1
    private const val HIGH_Q = 0.9
    private const val EPS = 1e-9
}
