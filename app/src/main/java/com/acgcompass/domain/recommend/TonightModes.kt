package com.acgcompass.domain.recommend

import com.acgcompass.domain.recommend.intent.IntentTagDictionary
import com.acgcompass.domain.recommend.intent.TagRepresentativeness

/**
 * 「今晚看什么」的两种模式，各管一件事（可以同时开，条件叠加）：
 *
 * - **低负担**：看起来不费劲。投入少（已完结、一季以内）、好跟上（不是要先看前作的续作，也不是烧脑、头绪多的作品）。
 * - **深夜**：看完睡得着。情绪平稳（不致郁、不压抑、不吓人），随时能停（不是一集接一集停不下来的悬疑 / 上头剧情），
 *   轻松日常的往前排。
 *
 * 气质按标签的代表性判断（[TagRepresentativeness]：标注人数 + 名次）：主打这种气质（≥ [STRONG]）的直接排除，
 * 只沾一点（≥ [WEAK]）的往后排；用户这次主动选了的标签不算冲突。
 */
object TonightModes {

    /** 低负担要避开的「费脑子、头绪多」。 */
    val BURDEN_TAGS: Set<String> = setOf("烧脑", "意识流", "群像", "硬科幻", "智斗", "权谋", "政治", "哲学")

    /** 深夜要避开的「情绪重、吓人」。 */
    val HEAVY_TAGS: Set<String> = setOf(
        "致郁", "致郁系", "虐心", "虐", "胃痛", "黑深残", "刀", "压抑", "悲剧", "战争", "残酷",
        "黑暗", "暗黑", "血腥", "猎奇", "恐怖", "惊悚", "重口",
    )

    /** 深夜要避开的「停不下来」。 */
    val BINGE_TAGS: Set<String> = setOf("悬疑", "推理", "上头", "反转", "大逃杀")

    /** 深夜优先的「轻松、能随时停」。 */
    val CALM_TAGS: Set<String> = setOf("日常", "治愈", "搞笑", "轻松", "温馨", "单元剧", "泡面番", "喜剧")

    /** 主打：代表性达到这个值直接排除。 */
    const val STRONG = 0.4

    /** 沾边：代表性达到这个值往后排（惩罚随代表性线性增加）。 */
    const val WEAK = 0.2

    /** 低负担接受的最多集数（一季）。 */
    const val MAX_EPISODES = 13

    /** 一部作品在开着的模式下的结论。 */
    data class Verdict(
        /** 不为 null 时排除，值是原因（如「主打致郁」）。 */
        val excludedBy: String? = null,
        /** 沾边的往后排：0–1，叠加到今晚分的主气质冲突里。 */
        val penalty: Double = 0.0,
        /** 深夜模式下轻松日常的程度 0–1，排序时略微往前。 */
        val calm: Double = 0.0,
    )

    /**
     * @param lowBurden 低负担模式是否开启。
     * @param lateNight 深夜模式是否开启。
     * @param rep 作品的标签代表性。
     * @param selectedTags 用户这次主动选的标签（已清洗）；选了的不算冲突。
     * @param finished 是否已完结；null = 不知道。
     * @param episodes 集数；null 或 0 = 不知道。
     * @param sequel 是否是第二季及以后（见 [isSequel]）。
     * @param prequelWatched 同系列是否已经看过（看过前作的续作不算负担）。
     */
    @Suppress("LongParameterList") // 两个模式各自要的几项作品信息，拆成对象反而难读
    fun verdict(
        lowBurden: Boolean,
        lateNight: Boolean,
        rep: TagRepresentativeness,
        selectedTags: Set<String> = emptySet(),
        finished: Boolean? = null,
        episodes: Int? = null,
        sequel: Boolean = false,
        prequelWatched: Boolean = false,
    ): Verdict {
        if (!lowBurden && !lateNight) return Verdict()
        val chosen = selectedTags.mapTo(HashSet()) { IntentTagDictionary.normalize(it) }
        val avoid = buildSet {
            if (lowBurden) addAll(BURDEN_TAGS)
            if (lateNight) addAll(HEAVY_TAGS + BINGE_TAGS)
        }.filterTo(HashSet()) { it !in chosen }
        val structural = if (lowBurden) lowBurdenBlocker(finished, episodes, sequel, prequelWatched) else null
        val strongest = rep.strongest(avoid)
        val calm = if (lateNight) rep.strongest(CALM_TAGS)?.second ?: 0.0 else 0.0
        return when {
            structural != null -> Verdict(excludedBy = structural, calm = calm)
            strongest != null && strongest.second >= STRONG -> Verdict(
                excludedBy = "主打「${strongest.first}」",
                calm = calm,
            )
            strongest != null && strongest.second >= WEAK ->
                Verdict(
                    penalty = ((strongest.second - WEAK) / (STRONG - WEAK)).coerceIn(0.0, 1.0) * MAX_PENALTY,
                    calm = calm,
                )
            else -> Verdict(calm = calm)
        }
    }

    /** 低负担对作品形态的要求：已完结、一季以内、不用先看前作。不满足时返回原因。 */
    fun lowBurdenBlocker(finished: Boolean?, episodes: Int?, sequel: Boolean, prequelWatched: Boolean): String? = when {
        finished == false -> "还没完结"
        (episodes ?: 0) > MAX_EPISODES -> "超过 $MAX_EPISODES 集"
        sequel && !prequelWatched -> "续作，要先看前作"
        else -> null
    }

    /** 第二季及以后（第 2 季、第2部分、2nd Season、Part 2、Ⅱ、续…）。 */
    fun isSequel(titles: List<String>): Boolean =
        com.acgcompass.domain.matching.seasonNumberOf(titles.filter { it.isNotBlank() }) >= 2

    /** 沾边作品的最大惩罚（和主气质冲突同一量纲）。 */
    private const val MAX_PENALTY = 0.6
}
