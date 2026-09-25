package com.acgcompass.domain.recommend

import com.acgcompass.domain.taste.TasteScoringParams

/**
 * 「我有多少时间」指的是今晚这一次能看多久，不是整部作品的总时长。
 *
 * - 时间够看完：最合适（短片、泡面番、剧场版）。
 * - 看不完但每集相对独立（日常、搞笑、单元剧、泡面番）：看一两集就能停，短时间也合适。
 * - 看不完且剧情连贯（悬疑、推理、群像剧情）：看一集停下来很难受，时间越短越往后排。
 */
@Suppress("MagicNumber", "ReturnCount")
object SessionFit {

    enum class Kind {
        /** 今晚能完整看完。 */
        FINISH,

        /** 一部电影的长度，今晚能看完。 */
        MOVIE,

        /** 看不完，但每集相对独立，看几集就能停。 */
        EPISODIC,

        /** 看不完，只能开个头。 */
        START,

        /** 单集都放不进这段时间。 */
        TOO_LONG,

        /** 缺少时长资料。 */
        UNKNOWN,
    }

    data class Result(val score: Double, val kind: Kind)

    /** 每集相对独立、可以随时停下的题材。 */
    val EPISODIC_TAGS: Set<String> = setOf("日常", "搞笑", "喜剧", "治愈", "单元剧", "泡面番", "短片", "欢乐", "萌系")

    /** 剧情连贯、看一集很难停下的题材。 */
    val SERIAL_TAGS: Set<String> = setOf("悬疑", "推理", "剧情", "群像", "智斗", "烧脑", "战争", "致郁")

    private const val WEEKEND_MINUTES = 600
    private const val SINGLE_EPISODE_SLACK = 1.25
    private const val FINISH_SLACK = 1.15
    private const val SHORT_EPISODE_MINUTES = 15
    private const val MOVIE_MINUTES = 60

    /**
     * @param budgetMinutes 可用分钟；`null` 表示周末不限时，按 [WEEKEND_MINUTES] 计。
     * @param tags 作品标签，按热度从高到低。
     */
    fun evaluate(budgetMinutes: Int?, episodes: Int?, episodeMinutes: Int?, tags: List<String>): Result {
        val budget = budgetMinutes ?: WEEKEND_MINUTES
        val eps = episodes?.takeIf { it > 0 }
        val perEp = episodeMinutes?.takeIf { it > 0 }
            ?: if (eps != null) TasteScoringParams.RUNTIME_TV_PER_EP else null
        if (perEp == null) return Result(0.5, Kind.UNKNOWN)
        if (perEp > budget * SINGLE_EPISODE_SLACK) return Result(0.05, Kind.TOO_LONG)

        val total = eps?.let { it * perEp }
        return if (total != null && total <= budget * FINISH_SLACK) {
            finishable(total, budget, eps, perEp)
        } else {
            partial(budget, perEp, total, tags)
        }
    }

    private fun finishable(total: Int, budget: Int, eps: Int, perEp: Int): Result {
        val score = 0.7 + 0.3 * TasteScoringParams.timeFit(total, budget)
        val kind = if (eps == 1 && perEp >= MOVIE_MINUTES) Kind.MOVIE else Kind.FINISH
        return Result(score, kind)
    }

    /** 看不完时：每集独立的往前排，剧情连贯的按时间长短往后排。 */
    private fun partial(budget: Int, perEp: Int, total: Int?, tags: List<String>): Result {
        val episodic = perEp <= SHORT_EPISODE_MINUTES || isEpisodic(tags)
        val serial = !episodic && tags.take(4).any { it in SERIAL_TAGS }
        if (budget > 180) {
            val progress = total?.let { (budget.toDouble() / it).coerceIn(0.0, 1.0) } ?: 0.5
            return Result(0.35 + 0.25 * progress, Kind.START)
        }
        val short = budget <= 60
        return when {
            episodic -> Result(if (short) 0.75 else 0.6, Kind.EPISODIC)
            serial -> Result(if (short) 0.15 else 0.35, Kind.START)
            else -> Result(if (short) 0.4 else 0.45, Kind.START)
        }
    }

    /** 时间越短，「这段时间合不合适」越重要。 */
    fun weight(budgetMinutes: Int?): Double = when {
        budgetMinutes == null -> TasteScoringParams.TONIGHT_W_TIME_FIT
        budgetMinutes <= 60 -> 0.24
        budgetMinutes <= 180 -> 0.16
        else -> TasteScoringParams.TONIGHT_W_TIME_FIT
    }

    /** 推荐理由里的一句时间说明；没什么可说时返回 null。 */
    fun note(result: Result, totalMinutes: Int?): String? = when (result.kind) {
        Kind.FINISH -> totalMinutes?.let { "全长约 $it 分钟，今晚能看完" } ?: "今晚能看完"
        Kind.MOVIE -> "一部电影的长度，今晚能看完"
        Kind.EPISODIC -> "每集相对独立，看一两集就能停"
        Kind.START, Kind.TOO_LONG, Kind.UNKNOWN -> null
    }

    private fun isEpisodic(tags: List<String>): Boolean {
        val top = tags.take(6)
        return top.any { it in EPISODIC_TAGS } && top.take(4).none { it in SERIAL_TAGS }
    }
}
