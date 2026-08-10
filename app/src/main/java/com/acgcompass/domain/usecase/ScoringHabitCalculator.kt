package com.acgcompass.domain.usecase

/**
 * 评分习惯、口味称号与口味黑洞的纯统计结果。
 *
 * - [strictness]：评分严格度 `[0,1]`，越高越严格（平均分越低）。
 * - [avgScore]：平均分（10 分制）；无评分样本时为 `0f`。
 * - [highScoreRarity]：高分稀有度 `[0,1]`，越高表示越少给高分（8 分以上）。
 * - [commonScoreBand]：常见分段（如「7-8 分」）；无评分样本时为 `null`，不伪造。
 * - [titles]：口味称号 / 补番人格；低样本时谨慎产出。
 * - [blackHole]：口味黑洞——最常被搁置 / 抛弃的类型；无数据时为空，不伪造。
 */
data class ScoringHabitResult(
    val strictness: Float,
    val avgScore: Float,
    val highScoreRarity: Float,
    val commonScoreBand: String?,
    val titles: List<String>,
    val blackHole: List<String>,
)

/**
 * 评分习惯 / 口味称号 / 口味黑洞计算器（领域用例，纯 Kotlin、无 Android / IO 依赖）。
 *
 * 在 [TasteStatsCalculator] 的标签 / 搁置统计之上，进一步由用户评分推导评分习惯，并据高分标签与
 * 搁置类型生成口味称号与口味黑洞。设计为纯函数，便于单元测试（ 低样本置信由仓库侧承担）。
 *
 * 「不编造」约束：
 * - 无评分样本时严格度 / 平均分 / 高分稀有度均为 `0f`、常见分段为 `null`；
 * - 称号仅在样本量足够（[MIN_TITLE_SAMPLE]）时产出基于评分习惯的称号，避免低样本下的武断结论。
 *
 */
class ScoringHabitCalculator {

    /**
     * 计算评分习惯、口味称号与口味黑洞。
     *
     * @param records 归一化的口味导入记录（含个人评分 / 标签 / 状态）。
     * @param stats   [TasteStatsCalculator] 的标签 / 搁置统计结果（提供高分标签与搁置类型）。
     */
    operator fun invoke(records: List<TasteInputRecord>, stats: TasteStats): ScoringHabitResult {
        val ratings = records.mapNotNull { it.rating }.filter { it in MIN_SCORE..MAX_SCORE }
        val blackHole = stats.droppedTypes.take(MAX_BLACK_HOLE).map { it.name }

        if (ratings.isEmpty()) {
            // 无评分样本：评分习惯字段保持中性 / 缺失，绝不伪造；称号仅可来自非评分信号。
            return ScoringHabitResult(
                strictness = 0f,
                avgScore = 0f,
                highScoreRarity = 0f,
                commonScoreBand = null,
                titles = buildTitles(
                    strictness = 0f,
                    highScoreRarity = 0f,
                    ratedCount = 0,
                    stats = stats,
                    blackHole = blackHole,
                ),
                blackHole = blackHole,
            )
        }

        val avg = ratings.average().toFloat()
        val strictness = (1f - avg / MAX_SCORE).coerceIn(0f, 1f)
        val highCount = ratings.count { it >= TasteStatsCalculator.HIGH_SCORE_MIN }
        val highScoreRarity = (1f - highCount.toFloat() / ratings.size).coerceIn(0f, 1f)

        return ScoringHabitResult(
            strictness = strictness,
            avgScore = avg,
            highScoreRarity = highScoreRarity,
            commonScoreBand = commonScoreBand(ratings),
            titles = buildTitles(strictness, highScoreRarity, ratings.size, stats, blackHole),
            blackHole = blackHole,
        )
    }

    /** 常见分段：把评分按 2 分宽分桶，取样本最多的桶（如「7-8 分」）。 */
    private fun commonScoreBand(ratings: List<Int>): String? {
        if (ratings.isEmpty()) return null
        // 分桶下界：1-2 / 3-4 / 5-6 / 7-8 / 9-10。
        val bandCounts = ratings.groupingBy { (it - 1) / 2 }.eachCount()
        val topBand = bandCounts.maxByOrNull { it.value }?.key ?: return null
        val low = topBand * 2 + 1
        val high = (low + 1).coerceAtMost(MAX_SCORE)
        return "$low-$high 分"
    }

    /** 据评分习惯、高分标签与口味黑洞生成口味称号。 */
    private fun buildTitles(
        strictness: Float,
        highScoreRarity: Float,
        ratedCount: Int,
        stats: TasteStats,
        blackHole: List<String>,
    ): List<String> = buildList {
        if (ratedCount >= MIN_TITLE_SAMPLE) {
            when {
                strictness >= STRICT_THRESHOLD -> add("严格评分官")
                strictness <= LENIENT_THRESHOLD -> add("慷慨给分党")
            }
            if (highScoreRarity >= RARE_HIGH_THRESHOLD) add("惜分如金")
        }
        stats.highScoreTags.firstOrNull()?.let { add("「${it.name}」头号粉丝") }
        blackHole.firstOrNull()?.let { add("「$it」绝缘体") }
    }

    companion object {
        /** 评分有效区间（10 分制）。 */
        const val MIN_SCORE: Int = 1
        const val MAX_SCORE: Int = 10

        /** 产出基于评分习惯的称号所需的最小评分样本量（低于此值不武断下称号）。 */
        const val MIN_TITLE_SAMPLE: Int = 5

        /** 口味黑洞展示的最多类型数。 */
        const val MAX_BLACK_HOLE: Int = 3

        /** 严格 / 宽松称号阈值。 */
        const val STRICT_THRESHOLD: Float = 0.5f
        const val LENIENT_THRESHOLD: Float = 0.25f

        /** 高分稀有称号阈值。 */
        const val RARE_HIGH_THRESHOLD: Float = 0.8f
    }
}
