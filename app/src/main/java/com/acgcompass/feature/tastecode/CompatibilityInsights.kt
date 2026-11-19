package com.acgcompass.feature.tastecode

import com.acgcompass.domain.tastecode.CompatibilityResult
import com.acgcompass.domain.tastecode.ScoringStyle
import com.acgcompass.domain.tastecode.TasteCode
import kotlin.math.abs
import kotlin.math.roundToInt

/** 对比页「你们俩」卡片里的一条结论。 */
data class Insight(val title: String, val body: String)

/**
 * 从两份口味码里挑出有话题性的差异和共同点，写成一句句结论：打分松紧、爱憎分明、看不看口碑、
 * 偏爱的年代、阅片量、最看重的口味维度、共同代表作的默契程度、雷点冲突。
 * 只用口味码里的数据，所以双方看到的结论互为镜像。
 */
@Suppress("TooManyFunctions") // 每类结论一个小函数
object CompatibilityInsights {
    private const val LENIENT = 0.3f
    private const val SCALE_GAP = 0.8f
    private const val SPREAD_SHARP = 1.8f
    private const val SPREAD_MILD = 1.1f
    private const val CROWD_HIGH = 1.1f
    private const val CROWD_LOW = 0.6f
    private const val ERA_GAP_YEARS = 5
    private const val COUNT_RATIO = 2.0
    private const val DIMENSION_GAP = 0.25f
    private const val AGREE_DIFF = 1
    private const val MIN_COUNT_FOR_RATIO = 10
    private const val DIVERGENCE_MIN = 0.2f

    /** 年代分档的代表年份，与 [TasteCode.ERA_LABELS] 对应。 */
    @Suppress("MagicNumber")
    private val ERA_MIDPOINTS = intArrayOf(1985, 1995, 2002, 2007, 2012, 2017, 2022, 2026)

    /** 口味维度的中文名（[com.acgcompass.domain.taste.TasteCategory.key]）。 */
    val DIMENSION_LABELS: Map<String, String> = linkedMapOf(
        "topic" to "题材",
        "combo" to "题材组合",
        "device" to "情节套路",
        "xp" to "角色属性",
        "character" to "具体角色",
        "staff" to "制作阵容",
        "cv" to "声优",
        "source" to "改编来源",
    )

    fun of(mine: TasteCode, other: TasteCode, result: CompatibilityResult, otherName: String): List<Insight> =
        listOfNotNull(
            scale(mine.style, other.style, otherName),
            spread(mine.style, other.style, otherName),
            crowd(mine.style, other.style, otherName),
            era(mine, other, otherName),
            count(mine.style, other.style, otherName),
            dimension(mine, other, otherName),
            agreement(result, otherName),
            clash(result, otherName),
            divergence(mine, other),
        )

    private fun scale(a: ScoringStyle, b: ScoringStyle, other: String): Insight {
        val gap = a.mean - b.mean
        val body = "你${leniencyText(a.leniency)}，${other}${leniencyText(b.leniency)}。" + when {
            gap >= SCALE_GAP -> "同一部作品，你给的分平均比${other}高 ${fmt(gap)} 分。"
            gap <= -SCALE_GAP -> "同一部作品，${other}给的分平均比你高 ${fmt(-gap)} 分。"
            else -> "打分的松紧差不多，分数可以直接比。"
        }
        return Insight("打分松紧", body)
    }

    private fun leniencyText(leniency: Float): String = when {
        leniency >= LENIENT -> "比大众手松 ${fmt(leniency)} 分"
        leniency <= -LENIENT -> "比大众手紧 ${fmt(-leniency)} 分"
        else -> "和大众差不多"
    }

    private fun spread(a: ScoringStyle, b: ScoringStyle, other: String): Insight? {
        fun kind(style: ScoringStyle) = when {
            style.spread >= SPREAD_SHARP -> "爱憎分明"
            style.spread <= SPREAD_MILD -> "温和派"
            else -> "收放有度"
        }
        if (a.ratedCount < 2 || b.ratedCount < 2) return null
        val mine = kind(a)
        val theirs = kind(b)
        val body = if (mine == theirs) {
            "你们都是「$mine」：分数的拉开程度差不多（你 ${fmt(a.spread)}、$other ${fmt(b.spread)}）。"
        } else {
            "你是「$mine」（分数标准差 ${fmt(a.spread)}），${other}是「$theirs」（${fmt(b.spread)}）。"
        }
        return Insight("爱憎程度", body)
    }

    private fun crowd(a: ScoringStyle, b: ScoringStyle, other: String): Insight {
        fun kind(style: ScoringStyle) = when {
            style.followsCrowd >= CROWD_HIGH -> "很看口碑，高分作品基本都给高分"
            style.followsCrowd <= CROWD_LOW -> "不太在乎大众评分，只看自己喜不喜欢"
            else -> "和大众口碑大体同步"
        }
        val mine = kind(a)
        val theirs = kind(b)
        val body = if (mine == theirs) "你们都$mine。" else "你$mine；${other}$theirs。"
        return Insight("跟不跟口碑", body)
    }

    private fun era(a: TasteCode, b: TasteCode, other: String): Insight? {
        val mine = averageYear(a.eraShares)
        val theirs = averageYear(b.eraShares)
        if (mine == null || theirs == null) return null
        val body = when {
            mine + ERA_GAP_YEARS <= theirs ->
                "你看的作品偏早（平均在 $mine 年前后），${other}更偏新番（平均 $theirs 年）。"
            theirs + ERA_GAP_YEARS <= mine ->
                "${other}看的作品偏早（平均在 $theirs 年前后），你更偏新番（平均 $mine 年）。"
            else -> "你们看的作品年代差不多，都集中在 ${(mine + theirs) / 2} 年前后。"
        }
        return Insight("偏爱的年代", body)
    }

    private fun averageYear(shares: List<Float>): Int? {
        val total = shares.sum()
        return if (shares.size != ERA_MIDPOINTS.size || total <= 0f) {
            null
        } else {
            (shares.indices.sumOf { (shares[it] * ERA_MIDPOINTS[it]).toDouble() } / total).roundToInt()
        }
    }

    private fun count(a: ScoringStyle, b: ScoringStyle, other: String): Insight {
        val body = when {
            b.ratedCount >= MIN_COUNT_FOR_RATIO && a.ratedCount >= b.ratedCount * COUNT_RATIO ->
                "你评过 ${a.ratedCount} 部，是${other}的 ${ratio(a.ratedCount, b.ratedCount)} 倍，可以带带 Ta。"
            a.ratedCount >= MIN_COUNT_FOR_RATIO && b.ratedCount >= a.ratedCount * COUNT_RATIO ->
                "${other}评过 ${b.ratedCount} 部，是你的 ${ratio(b.ratedCount, a.ratedCount)} 倍，找 Ta 要片单准没错。"
            else -> "你评过 ${a.ratedCount} 部，${other}评过 ${b.ratedCount} 部，阅片量不相上下。"
        }
        return Insight("阅片量", body)
    }

    private fun ratio(big: Int, small: Int): String = fmt(big.toFloat() / small.coerceAtLeast(1))

    private fun dimension(a: TasteCode, b: TasteCode, other: String): Insight? {
        val keys = DIMENSION_LABELS.keys.filter { it in a.dimensions && it in b.dimensions }
        if (keys.isEmpty()) return null
        val mineTop = keys.maxBy { a.dimensions.getValue(it) }
        val theirTop = keys.maxBy { b.dimensions.getValue(it) }
        val mineMore = keys.maxBy { a.dimensions.getValue(it) - b.dimensions.getValue(it) }
        val theirMore = keys.maxBy { b.dimensions.getValue(it) - a.dimensions.getValue(it) }
        val gapMine = a.dimensions.getValue(mineMore) - b.dimensions.getValue(mineMore)
        val gapTheirs = b.dimensions.getValue(theirMore) - a.dimensions.getValue(theirMore)
        val head = if (mineTop == theirTop) {
            "你们最看重的都是${label(mineTop)}。"
        } else {
            "你最看重${label(mineTop)}，${other}最看重${label(theirTop)}。"
        }
        val tail = if (gapMine >= DIMENSION_GAP && gapTheirs >= DIMENSION_GAP && mineMore != theirMore) {
            "比起对方，你更在意${label(mineMore)}，${other}更在意${label(theirMore)}。"
        } else {
            ""
        }
        return Insight("看重什么", head + tail)
    }

    private fun label(key: String): String = "「${DIMENSION_LABELS[key] ?: key}」"

    private fun agreement(result: CompatibilityResult, other: String): Insight? {
        val common = result.commonWorks
        if (common.isEmpty()) return null
        val agreed = common.count { abs(it.second - it.third) <= AGREE_DIFF }
        val body = "你们都放进代表作的有 ${common.size} 部，其中 $agreed 部评分相差不超过 1 分。" + when {
            agreed == common.size -> "评分默契满分。"
            agreed * 2 >= common.size -> "大部分时候想法一致。"
            else -> "同一部作品，你和${other}的看法经常不一样。"
        }
        return Insight("共同作品", body)
    }

    private fun clash(result: CompatibilityResult, other: String): Insight? {
        val mineLikesTheirAvoid = result.clashes.first.firstOrNull()
        val theirLikesMineAvoid = result.clashes.second.firstOrNull()
        val body = when {
            mineLikesTheirAvoid != null && theirLikesMineAvoid != null ->
                "「$mineLikesTheirAvoid」是你的菜、${other}的雷；「$theirLikesMineAvoid」正好反过来。安利之前先避开。"
            mineLikesTheirAvoid != null -> "「$mineLikesTheirAvoid」是你的菜，却是${other}的雷，安利时绕开它。"
            theirLikesMineAvoid != null -> "「$theirLikesMineAvoid」是${other}的菜，却是你的雷。"
            else -> return null
        }
        return Insight("雷区", body)
    }

    private fun divergence(a: TasteCode, b: TasteCode): Insight? {
        val theirs = b.divergence.associate { it.tag to it.weight }
        val same = a.divergence.mapNotNull { tag ->
            val other = theirs[tag.tag] ?: return@mapNotNull null
            val bothStrong = abs(other) >= DIVERGENCE_MIN && abs(tag.weight) >= DIVERGENCE_MIN
            if (other * tag.weight > 0 && bothStrong) tag.tag to (tag.weight > 0) else null
        }
        val higher = same.filter { it.second }.take(2).map { "「${it.first}」" }
        val lower = same.filterNot { it.second }.take(2).map { "「${it.first}」" }
        val parts = listOfNotNull(
            higher.takeIf { it.isNotEmpty() }?.let { "带${it.joinToString("、")}的作品，你们都比大众打分更高" },
            lower.takeIf { it.isNotEmpty() }?.let { "对${it.joinToString("、")}，你们都比大众更挑剔" },
        )
        return if (parts.isEmpty()) null else Insight("共同的小众偏好", parts.joinToString("；") + "。")
    }

    private fun fmt(value: Float): String = "%.1f".format(value)
}
