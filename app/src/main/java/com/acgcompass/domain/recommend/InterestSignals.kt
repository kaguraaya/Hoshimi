package com.acgcompass.domain.recommend

import kotlin.math.pow

/**
 * 兴趣层：「现在想不想看」。
 *
 * 输入是探索队列的加入 / 暂不和收藏状态（想看 / 在看 / 搁置 / 抛弃），按种类给权重、按时间衰减，
 * 汇成标签级的加入倾向（[ExploreRanker.feedbackModel]）。只给探索队列排序用：
 * - 不进预计评分和口味匹配度（想看不等于会打高分）；
 * - 不生成新的口味画像，滑动后也不触发画像重建，所以打分缓存和为你排行不会因此失效。
 * 「曝光了但没滑」不算反馈（既不是加入也不是拒绝），只用于冷却。
 */
object InterestSignals {
    enum class Kind(val liked: Boolean, val weight: Double) {
        /** 探索队列里加入待补池：明确表态。 */
        EXPLORE_LIKE(liked = true, weight = 1.0),

        /** 探索队列里点「暂不」：原因不明，只算半次。 */
        EXPLORE_SKIP(liked = false, weight = ExploreRanker.SKIP_WEIGHT),

        /** 收藏为「想看」：有兴趣，但不如在探索里当场加入那样是对这张卡片的直接回应。 */
        WISH(liked = true, weight = 0.6),

        /** 「在看」：已经开始看，兴趣比想看更实。 */
        WATCHING(liked = true, weight = 0.8),

        /** 「搁置」：看了一部分停下来，弱的负面信号。 */
        ON_HOLD(liked = false, weight = 0.4),

        /** 「抛弃」：看过之后放弃，负面信号里最强的一种。 */
        DROPPED(liked = false, weight = 1.0),
    }

    /** 衰减半衰期：三个月前的一次表态只算半次。 */
    const val HALF_LIFE_DAYS = 90.0

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun feedback(kind: Kind, tags: Set<String>, eventAtMs: Long, nowMs: Long): ExploreRanker.Feedback =
        ExploreRanker.Feedback(tags, kind.liked, kind.weight * decay(nowMs - eventAtMs))

    /** 收藏状态（中文名）对应的兴趣信号；「看过」等不是兴趣信号，返回 `null`。 */
    fun statusKind(status: String?): Kind? = when (status) {
        "想看" -> Kind.WISH
        "在看" -> Kind.WATCHING
        "搁置" -> Kind.ON_HOLD
        "抛弃" -> Kind.DROPPED
        else -> null
    }

    internal fun decay(ageMs: Long): Double {
        val days = ageMs.coerceAtLeast(0L).toDouble() / DAY_MS
        return 0.5.pow(days / HALF_LIFE_DAYS)
    }
}
