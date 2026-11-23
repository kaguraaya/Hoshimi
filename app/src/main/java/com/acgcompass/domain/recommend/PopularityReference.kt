package com.acgcompass.domain.recommend

import java.time.LocalDate
import kotlin.math.log10

/**
 * 热度：一部作品的 Bangumi 评分人数在**同一年开播的 TV 动画**里排在什么位置（0–1，越大越热门）。
 *
 * 评分人数随时间累积，而且 Bangumi 的用户规模这些年一直在变：同样 3000 人，放在今年的新番里已经是前一成，
 * 放在 2010 年的作品里只算中上。所以不用固定人数分档，而是和同年作品比。参照表 [BANGUMI] 来自 Bangumi
 * 公开接口按开播年月浏览的全部 TV 动画（去掉 R18 和开播不满 [SETTLING_DAYS] 天的），每年存 11 个分位点，
 * 中间按评分人数的对数插值。
 *
 * 开播不满 [SETTLING_DAYS] 天的作品热度记为不知道：大多数人看完一季才打分，播出期间的评分人数只有最终的一小部分。
 */
object PopularityReference {

    /** 参照表：[percentiles] 是分位点，[years] 是每个开播年份在这些分位点上的评分人数。 */
    class Table(val percentiles: DoubleArray, val years: Map<Int, IntArray>) {
        private val first = years.keys.min()
        private val last = years.keys.max()

        /** 某年的分位点；超出范围用最近的一年（今年之后的作品用表里最新的一年）。 */
        fun forYear(year: Int): IntArray = years[year.coerceIn(first, last)]
            ?: years.minBy { kotlin.math.abs(it.key - year) }.value
    }

    /** 热度档，阈值见 [tierOf]。 */
    enum class Tier(val label: String) { COLD("冷门"), NORMAL("普通"), HOT("热门"), TOP("霸权") }

    /** 开播后多少天内热度记为不知道（还在累积评分）。 */
    const val SETTLING_DAYS = 150L

    /**
     * 热度 0–1；评分人数未知（≤ 0）、开播日期未知或开播不满 [SETTLING_DAYS] 天时返回 null。
     *
     * @param airDay 开播日（[airDay] 换算的纪元日）。
     */
    fun percentile(votes: Int, airDay: Long?, today: LocalDate, table: Table = BANGUMI): Double? {
        val settled = airDay?.takeIf { votes > 0 && today.toEpochDay() - it >= SETTLING_DAYS } ?: return null
        val knots = table.forYear(LocalDate.ofEpochDay(settled).year)
        val p = table.percentiles
        val lv = log10(votes.toDouble())
        val xs = DoubleArray(knots.size + 2)
        val ps = DoubleArray(knots.size + 2)
        xs[0] = 0.0
        ps[0] = 0.0
        for (i in knots.indices) {
            xs[i + 1] = log10(knots[i].coerceAtLeast(1).toDouble())
            ps[i + 1] = p[i]
        }
        xs[xs.lastIndex] = xs[xs.lastIndex - 1] + 1.0
        ps[ps.lastIndex] = 1.0
        return interpolate(lv, xs, ps)
    }

    /**
     * 热度档：前 15% 霸权、前 25% 热门、前 45% 普通，其余冷门。按参照表，2024–2026 年开播的 TV 动画里
     * 1000 / 2000 / 3000 人大约落在第 55 / 75 / 83 百分位，所以对现在的新番来说，三条线大约就是 1000 / 2000 / 3000 人。
     */
    fun tierOf(percentile: Double): Tier = when {
        percentile >= TOP_AT -> Tier.TOP
        percentile >= HOT_AT -> Tier.HOT
        percentile >= NORMAL_AT -> Tier.NORMAL
        else -> Tier.COLD
    }

    /** 开播日（纪元日）：有「年-月-日」或「年-月」用它，否则用年份的 7 月 1 日；都没有返回 null。 */
    fun airDay(airDate: String?, year: Int?): Long? {
        val parts = airDate?.trim()?.split('-').orEmpty()
        val y = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in MIN_YEAR..MAX_YEAR }
        if (y != null) {
            val m = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..MONTHS } ?: MID_MONTH
            val days = LocalDate.of(y, m, 1).lengthOfMonth()
            val d = parts.getOrNull(2)?.take(2)?.toIntOrNull()?.takeIf { it in 1..days } ?: 1
            return LocalDate.of(y, m, d).toEpochDay()
        }
        return year?.takeIf { it in MIN_YEAR..MAX_YEAR }?.let { LocalDate.of(it, MID_MONTH, 1).toEpochDay() }
    }

    /** 分段线性插值；[xs] 不递减，超出两端取端点。 */
    private fun interpolate(x: Double, xs: DoubleArray, ps: DoubleArray): Double {
        val i = (0 until xs.lastIndex).firstOrNull { x <= xs[it + 1] }
        return when {
            x <= xs[0] -> ps[0]
            i == null -> ps.last()
            xs[i + 1] - xs[i] <= 0.0 -> ps[i + 1]
            else -> ps[i] + (ps[i + 1] - ps[i]) * (x - xs[i]) / (xs[i + 1] - xs[i])
        }
    }

    private const val TOP_AT = 0.85
    private const val HOT_AT = 0.75
    private const val NORMAL_AT = 0.55
    private const val MIN_YEAR = 1900
    private const val MAX_YEAR = 2100
    private const val MONTHS = 12
    private const val MID_MONTH = 7

    /** Bangumi 公开接口，2026-11 抓取：各开播年份 TV 动画评分人数在这些分位点上的值。 */
    @Suppress("MagicNumber") // 统计数据本身
    val BANGUMI: Table = Table(
        percentiles = doubleArrayOf(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 0.98),
        years = mapOf(
            1990 to intArrayOf(2, 3, 6, 9, 15, 30, 53, 59, 337, 943, 1077),
            1991 to intArrayOf(1, 2, 4, 6, 9, 16, 32, 43, 73, 181, 299),
            1992 to intArrayOf(1, 2, 4, 6, 9, 15, 32, 67, 109, 835, 1971),
            1993 to intArrayOf(2, 4, 4, 8, 24, 39, 69, 116, 355, 869, 3940),
            1994 to intArrayOf(2, 2, 4, 9, 15, 23, 62, 130, 275, 364, 1054),
            1995 to intArrayOf(1, 2, 6, 9, 26, 50, 94, 192, 517, 1462, 1893),
            1996 to intArrayOf(2, 5, 19, 32, 44, 66, 131, 339, 1209, 1933, 2715),
            1997 to intArrayOf(2, 6, 16, 24, 32, 55, 124, 264, 953, 2074, 4654),
            1998 to intArrayOf(5, 7, 11, 17, 28, 46, 92, 169, 476, 1865, 8295),
            1999 to intArrayOf(2, 4, 9, 20, 42, 77, 149, 270, 1117, 2097, 4719),
            2000 to intArrayOf(3, 7, 14, 25, 62, 84, 166, 316, 601, 1279, 3255),
            2001 to intArrayOf(2, 4, 8, 17, 38, 75, 115, 254, 661, 1842, 2606),
            2002 to intArrayOf(5, 12, 26, 55, 105, 185, 314, 671, 1635, 4158, 5986),
            2003 to intArrayOf(3, 17, 33, 51, 88, 133, 274, 480, 1168, 1775, 4390),
            2004 to intArrayOf(3, 13, 32, 63, 112, 174, 344, 691, 1634, 4294, 5664),
            2005 to intArrayOf(6, 28, 45, 80, 165, 309, 528, 1026, 2509, 4802, 6725),
            2006 to intArrayOf(5, 13, 40, 83, 145, 255, 501, 876, 3003, 5834, 14258),
            2007 to intArrayOf(3, 20, 52, 145, 258, 404, 684, 1361, 4323, 7080, 12411),
            2008 to intArrayOf(4, 15, 46, 117, 368, 614, 1042, 1753, 3267, 5610, 13677),
            2009 to intArrayOf(3, 10, 35, 165, 356, 607, 979, 1397, 3533, 7100, 21896),
            2010 to intArrayOf(2, 11, 34, 89, 270, 764, 1482, 2327, 5892, 10478, 15406),
            2011 to intArrayOf(5, 20, 50, 154, 297, 1013, 2077, 3399, 6798, 11006, 23032),
            2012 to intArrayOf(3, 13, 57, 165, 310, 863, 1481, 2978, 5866, 9304, 18893),
            2013 to intArrayOf(6, 26, 84, 211, 531, 1048, 1744, 3095, 6119, 10104, 15343),
            2014 to intArrayOf(3, 14, 43, 124, 410, 852, 1587, 3289, 5573, 10453, 17651),
            2015 to intArrayOf(2, 13, 39, 134, 374, 842, 1559, 3118, 4896, 9314, 18528),
            2016 to intArrayOf(2, 11, 32, 104, 226, 504, 902, 1859, 3909, 6645, 14186),
            2017 to intArrayOf(2, 6, 13, 42, 169, 434, 1183, 2535, 6071, 9729, 16717),
            2018 to intArrayOf(3, 8, 31, 119, 259, 628, 1285, 3255, 6655, 11968, 16601),
            2019 to intArrayOf(2, 5, 23, 106, 296, 623, 1401, 3250, 5864, 9416, 13176),
            2020 to intArrayOf(2, 4, 10, 58, 262, 584, 1423, 3376, 5868, 8647, 15949),
            2021 to intArrayOf(2, 8, 41, 182, 382, 1042, 1945, 3414, 6441, 10857, 15358),
            2022 to intArrayOf(2, 11, 103, 265, 648, 1218, 2119, 3252, 7075, 12378, 20137),
            2023 to intArrayOf(2, 10, 109, 438, 899, 1358, 2013, 2971, 5740, 10729, 15497),
            2024 to intArrayOf(2, 9, 132, 424, 840, 1162, 1844, 2597, 5720, 8453, 14312),
            2025 to intArrayOf(4, 34, 167, 472, 790, 1123, 1617, 2879, 5862, 8257, 10130),
            2026 to intArrayOf(5, 110, 278, 529, 764, 1042, 1454, 2297, 3475, 7221, 9064),
        ),
    )
}
