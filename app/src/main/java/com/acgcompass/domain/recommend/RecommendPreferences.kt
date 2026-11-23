package com.acgcompass.domain.recommend

/**
 * 推荐偏好（设置 → 推荐偏好）：探索队列和「今晚看什么」在平常的排序之上额外看重什么。
 *
 * 平常的排序（默认）按「预计满意度」：预计你会打的分在你自己打分习惯里的位置。偏好只在它之上加减，
 * **全部保持默认时排序和没有这个设置时完全一样**。怎么加减见 [PreferenceRanking]：每一项先换成
 * 「在这次的候选里高出平均几个标准差」，再按档位的权重（[weightOf]）加到预计满意度上。
 *
 * @property taste 口味契合（口味匹配度：题材、相似作品、常看的圈子）：0 不看（不额外加权）、1 低、2 中、3 高。
 * @property community 社区口碑（Bangumi 社区分，评分人数少的往中间收）：[UNDERRATED] = 被低估，0–3 同上。
 * @property underratedBelow 「被低估」只看社区分低于多少的作品（×10 存，65 / 70 / 75）。
 * @property freshness 新番（首播越近越靠前）：0–3 同上。
 * @property obscurity 冷门（同一年开播的 TV 动画里评分人数越靠后越往前，见 [PopularityReference]）：0–3 同上，
 *   权重是其它几项的一半。
 * @property discovery 探索队列每批留给「口碑好但不在你常看题材里」的名额：0 不留、1 少、2 默认、3 多。
 */
data class RecommendPreferences(
    val taste: Int = 0,
    val community: Int = 0,
    val underratedBelow: Int = DEFAULT_UNDERRATED_BELOW,
    val freshness: Int = 0,
    val obscurity: Int = 0,
    val discovery: Int = DEFAULT_DISCOVERY,
) {
    /** 把越界的值收回合法范围。 */
    fun normalized(): RecommendPreferences = copy(
        taste = taste.coerceIn(0, MAX_LEVEL),
        community = community.coerceIn(UNDERRATED, MAX_LEVEL),
        underratedBelow = UNDERRATED_THRESHOLDS.minByOrNull { kotlin.math.abs(it - underratedBelow) }
            ?: DEFAULT_UNDERRATED_BELOW,
        freshness = freshness.coerceIn(0, MAX_LEVEL),
        obscurity = obscurity.coerceIn(0, MAX_LEVEL),
        discovery = discovery.coerceIn(0, MAX_DISCOVERY),
    )

    val isDefault: Boolean get() = normalized() == DEFAULT

    /** 是否开着「被低估」：只看社区分低于 [underratedBelow] 的作品。 */
    val underrated: Boolean get() = community == UNDERRATED

    /** 「被低估」的社区分上限（如 7.0）。 */
    val underratedThreshold: Double get() = underratedBelow / TENTHS

    /** 各项的权重（标准差单位）；全为 0 时排序不变。「被低估」时口碑本身不加权（只用来筛选）。 */
    val tasteWeight: Double get() = weightOf(taste)
    val communityWeight: Double get() = if (underrated) 0.0 else weightOf(community)
    val freshnessWeight: Double get() = weightOf(freshness)
    val popularityWeight: Double get() = -OBSCURITY_WEIGHTS[obscurity.coerceIn(0, MAX_LEVEL)]

    /** 有没有任何一项要在平常的排序之上加减。 */
    val reranks: Boolean get() = tasteWeight != 0.0 || communityWeight != 0.0 || freshnessWeight != 0.0 ||
        popularityWeight != 0.0

    /** 探索队列跨界名额占比。 */
    val discoveryShare: Double get() = DISCOVERY_SHARES[discovery.coerceIn(0, MAX_DISCOVERY)]

    /** 「被低估」筛选：社区分已知、评分人数够多（分数站得住），且低于上限。不开时一律通过。 */
    fun passesUnderrated(score10: Double?, votes: Int): Boolean = !underrated ||
        (score10 != null && score10 > 0.0 && votes >= UNDERRATED_MIN_VOTES && score10 < underratedThreshold)

    /** 排序的几项（口味契合、口碑含被低估的线、新番、热度）是否和 [other] 一样；跨界名额不算。 */
    fun sameRanking(other: RecommendPreferences): Boolean =
        taste == other.taste && community == other.community && freshness == other.freshness &&
            obscurity == other.obscurity && (!underrated || underratedBelow == other.underratedBelow)

    /** 换成 [preset] 的排序几项，跨界名额保留。 */
    fun withRankingOf(preset: RecommendPreferences): RecommendPreferences = copy(
        taste = preset.taste,
        community = preset.community,
        underratedBelow = preset.underratedBelow,
        freshness = preset.freshness,
        obscurity = preset.obscurity,
    )

    /** 存进设置的文本（`v=3;taste=0;…`）；没有版本号的旧格式按默认处理。 */
    fun encode(): String = listOf(
        "v" to FORMAT_VERSION,
        "taste" to taste,
        "community" to community,
        "underratedBelow" to underratedBelow,
        "freshness" to freshness,
        "obscurity" to obscurity,
        "discovery" to discovery,
    ).joinToString(";") { (k, v) -> "$k=$v" }

    companion object {
        const val MAX_LEVEL = 3
        const val MAX_DISCOVERY = 3
        const val DEFAULT_DISCOVERY = 2

        /** 社区口碑的特殊档：被低估。 */
        const val UNDERRATED = -1
        const val DEFAULT_UNDERRATED_BELOW = 70

        /** 「被低估」可选的社区分上限（×10）。 */
        val UNDERRATED_THRESHOLDS: List<Int> = listOf(65, 70, 75)

        /** 「被低估」要求的最少评分人数：太少时社区分本身不可信。 */
        const val UNDERRATED_MIN_VOTES = 100

        /**
         * 档位 → 权重（标准差单位）：低 0.25、中 0.5、高 1。
         * 权重 w 的意思：这一项在候选里高 1 个标准差，抵得上预计满意度高 w 个标准差。
         * 离线评测里 1 已经接近「这一项和预计满意度一样重要」，再高会明显伤到推荐质量，所以不设更高档。
         */
        fun weightOf(level: Int): Double = LEVEL_WEIGHTS[level.coerceIn(0, MAX_LEVEL)]

        private val LEVEL_WEIGHTS = listOf(0.0, 0.25, 0.5, 1.0)

        /** 冷门的权重减半：低 0.125、中 0.25、高 0.5（离线评测里 1 时几乎推不出会看的作品）。 */
        private val OBSCURITY_WEIGHTS = listOf(0.0, 0.125, 0.25, 0.5)

        private val DISCOVERY_SHARES = listOf(0.0, 0.15, 0.3, 0.45)
        private const val FORMAT_VERSION = 3
        private const val PREVIOUS_FORMAT = 2
        private const val TENTHS = 10.0

        val DEFAULT = RecommendPreferences()

        /** 解析 [encode] 的文本；版本不对时用默认值，认不出的键忽略，缺的键用默认值，结果收回合法范围。 */
        fun decode(text: String?): RecommendPreferences {
            val values = text.orEmpty().split(';').mapNotNull { part ->
                val kv = part.split('=', limit = 2).takeIf { it.size == 2 }
                kv?.get(1)?.trim()?.toIntOrNull()?.let { kv[0].trim() to it }
            }.toMap()
            val version = values["v"]
            if (version != FORMAT_VERSION && version != PREVIOUS_FORMAT) return DEFAULT
            return RecommendPreferences(
                taste = values["taste"] ?: DEFAULT.taste,
                community = values["community"] ?: DEFAULT.community,
                underratedBelow = values["underratedBelow"] ?: DEFAULT.underratedBelow,
                freshness = values["freshness"] ?: DEFAULT.freshness,
                obscurity = values["obscurity"] ?: obscurityFromFormat2(values["popularity"]),
                discovery = values["discovery"] ?: DEFAULT.discovery,
            ).normalized()
        }

        /** 格式 2 用 popularity 存热度（−2…2）：−1 / −2 换成冷门的中 / 高，正值按不看处理。 */
        @Suppress("MagicNumber") // 两种格式之间的档位对照表
        private fun obscurityFromFormat2(popularity: Int?): Int = when (popularity) {
            -1 -> 2
            -2 -> 3
            else -> 0
        }

        /** 预设：一键切换排序的几项（跨界名额不动），切完还能逐项微调。 */
        val PRESETS: List<Pair<String, RecommendPreferences>> = listOf(
            "均衡（默认）" to DEFAULT,
            "更合口味" to RecommendPreferences(taste = 2),
            "社区低估" to RecommendPreferences(taste = 2, community = UNDERRATED),
            "口碑稳妥" to RecommendPreferences(community = 2),
            "新番优先" to RecommendPreferences(freshness = 2),
            "冷门但对口" to RecommendPreferences(taste = 2, obscurity = 2),
        )
    }
}
