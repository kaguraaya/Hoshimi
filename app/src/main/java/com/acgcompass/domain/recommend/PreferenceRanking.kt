package com.acgcompass.domain.recommend

import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 推荐偏好的重排（探索队列和「今晚看什么」共用，纯 Kotlin）。
 *
 * 几项分数的量纲各不相同（预计满意度 0–100、社区分 0–10、年份、热度分位），直接相加时谁的数值范围大谁说了算，
 * 档位也就没有固定含义。所以先把每一项换成**在这次候选里的正态分数**（排名 → 标准正态分位，
 * 即「高出平均几个标准差」；并列取平均名次，缺数据的按平均 0 处理），再按偏好的权重相加：
 *
 * `新分 = z(平常的排序分) + w口味·z(匹配度) + w口碑·z(社区分) + w新番·z(首播时间) + w热度·z(热度)`
 *
 * 用正态分数而不是名次百分位：探索队列一批只取十几部，名次百分位在头部挤在一起（第 1 名和第 280 名只差 0.1），
 * 一点权重就会把整批换掉；正态分数在头部拉得开，档位的效果是渐进的。
 *
 * 最后**按新分重新排队，再把这组候选自己的排序分按名次依次发回去**（第 1 名拿其中最高的分，依此类推）：
 * 分数的分布和平常一样，所以后面的多样性、随机挑选、滑动反馈这些步骤的力度不变，变的只是谁排在前面。
 * 所有权重都是 0 时原样返回。
 */
object PreferenceRanking {

    /**
     * 一部候选作品的各项原始值；缺数据填 null。
     *
     * @property base 平常的排序分（探索队列：预计满意度；今晚看什么：今晚分）。
     * @property match 口味匹配度（0–100）。
     * @property community 社区分（建议用评分人数收缩后的分数）。
     * @property freshness 越新越大的数（如首播日期的天数或年份）。
     * @property popularity 热度（[PopularityReference.percentile]）。
     */
    data class Factors(
        val id: String,
        val base: Double,
        val match: Double? = null,
        val community: Double? = null,
        val freshness: Double? = null,
        val popularity: Double? = null,
    )

    /** 重排后的排序分：id → 新的排序分（取自输入的那组排序分）。没有要加减的项时就是输入的分。 */
    fun rerank(items: List<Factors>, prefs: RecommendPreferences): Map<String, Double> {
        if (!prefs.reranks || items.size < 2) return items.associate { it.id to it.base }
        val combined = combine(items, prefs)
        val order = items.indices.sortedWith(
            compareByDescending<Int> { combined[it] }.thenByDescending { items[it].base }.thenBy { items[it].id },
        )
        val bases = items.map { it.base }.sortedDescending()
        return order.withIndex().associate { (rank, i) -> items[i].id to bases[rank] }
    }

    /** 「口碑」一项用的社区分：按评分人数往 6.5 收缩（等效 300 人，和今晚看什么的贝叶斯均分一致）。 */
    fun shrunkCommunity(score10: Double?, votes: Int): Double? {
        val s = score10?.takeIf { it > 0.0 } ?: return null
        val v = votes.coerceAtLeast(0).toDouble()
        return (v * s + PRIOR_VOTES * PRIOR_MEAN) / (v + PRIOR_VOTES)
    }

    /** 加权后的正态分数（只给评测和测试看；排序请用 [rerank]）。 */
    fun combine(items: List<Factors>, prefs: RecommendPreferences): DoubleArray {
        val out = normalScores(items.map { it.base })
        fun add(weight: Double, values: List<Double?>) {
            if (weight == 0.0) return
            val z = normalScores(values)
            for (i in out.indices) out[i] += weight * z[i]
        }
        add(prefs.tasteWeight, items.map { it.match })
        add(prefs.communityWeight, items.map { it.community })
        add(prefs.freshnessWeight, items.map { it.freshness })
        add(prefs.popularityWeight, items.map { it.popularity })
        return out
    }

    /**
     * 正态分数：已知值按名次（并列取平均名次）换成 Φ⁻¹((名次 − 0.5) / n)，未知值为 0。
     * 已知值少于两个时全部为 0（没法比较）。
     */
    fun normalScores(values: List<Double?>): DoubleArray {
        val out = DoubleArray(values.size)
        val known = values.indices.filter { values[it] != null && !values[it]!!.isNaN() }
        if (known.size < 2) return out
        val sorted = known.sortedBy { values[it]!! }
        val n = sorted.size
        var start = 0
        while (start < n) {
            var end = start
            while (end + 1 < n && values[sorted[end + 1]] == values[sorted[start]]) end++
            // 名次从 1 起；并列的一组取平均名次。
            val rank = (start + end) / 2.0 + 1.0
            val z = inverseNormal((rank - HALF) / n)
            for (k in start..end) out[sorted[k]] = z
            start = end + 1
        }
        return out
    }

    /** 标准正态分布的分位函数（Acklam 有理逼近，相对误差约 1e-9，够排序用）。 */
    @Suppress("MagicNumber") // 逼近公式的系数下标与常数
    fun inverseNormal(p: Double): Double {
        val q = p.coerceIn(P_MIN, 1.0 - P_MIN)
        return when {
            q < P_LOW -> {
                val t = sqrt(-2.0 * ln(q))
                (((((C[0] * t + C[1]) * t + C[2]) * t + C[3]) * t + C[4]) * t + C[5]) /
                    ((((D[0] * t + D[1]) * t + D[2]) * t + D[3]) * t + 1.0)
            }
            q > 1.0 - P_LOW -> -inverseNormal(1.0 - q)
            else -> {
                val r = q - HALF
                val s = r * r
                (((((A[0] * s + A[1]) * s + A[2]) * s + A[3]) * s + A[4]) * s + A[5]) * r /
                    (((((B[0] * s + B[1]) * s + B[2]) * s + B[3]) * s + B[4]) * s + 1.0)
            }
        }
    }

    private const val HALF = 0.5
    private const val PRIOR_VOTES = 300.0
    private const val PRIOR_MEAN = 6.5
    private const val P_MIN = 1e-12
    private const val P_LOW = 0.02425

    @Suppress("MagicNumber")
    private val A = doubleArrayOf(
        -3.969683028665376e+01,
        2.209460984245205e+02,
        -2.759285104469687e+02,
        1.383577518672690e+02,
        -3.066479806614716e+01,
        2.506628277459239e+00,
    )

    @Suppress("MagicNumber")
    private val B = doubleArrayOf(
        -5.447609879822406e+01,
        1.615858368580409e+02,
        -1.556989798598866e+02,
        6.680131188771972e+01,
        -1.328068155288572e+01,
    )

    @Suppress("MagicNumber")
    private val C = doubleArrayOf(
        -7.784894002430293e-03,
        -3.223964580411365e-01,
        -2.400758277161838e+00,
        -2.549732539343734e+00,
        4.374664141464968e+00,
        2.938163982698783e+00,
    )

    @Suppress("MagicNumber")
    private val D = doubleArrayOf(
        7.784695709041462e-03,
        3.224671290700398e-01,
        2.445134137142996e+00,
        3.754408661907416e+00,
    )
}
