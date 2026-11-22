package com.acgcompass.feature.detail

import com.acgcompass.domain.taste.RatingPrediction
import com.acgcompass.domain.taste.TasteMatchResult

// 详情页口味区的两句补充说明：已评分作品的回测值，以及匹配度和预计评分差得多时的解释。

/** 已评分作品的回测说明。 */
internal fun TasteMatchResult.backtestLine(): String {
    val bt = backtest ?: return if (backtestPending) {
        "回测计算中：一两分钟内显示「建画像时不知道这部作品」算出的匹配度和预计评分。"
    } else {
        "这部作品没有回测值（评过分的作品少于 ${com.acgcompass.domain.taste.TasteBacktest.MIN_RATED} 部，或缺少作品资料）；" +
            "上面的匹配度把你的评分也算进去了，只作参考。"
    }
    val match = bt.match?.let { "匹配度 $it%" } ?: "匹配度暂无"
    val predicted = (bt.prediction?.inlineText ?: bt.predicted?.let { "${"%.1f".format(it)} 分" })
        ?.let { " · 预计你会打 $it" }.orEmpty()
    val gap = bt.match?.let { m -> bt.prediction?.let { disagreementNote(m, it, circleZ) } }.orEmpty()
    val updating = if (bt.updating) "画像刚更新，正在重新回测，这里先显示上一次的结果。" else ""
    return "回测（建画像时不知道这部作品）：$match$predicted · 你的评分 ${bt.rating} 分。" +
        updating + "这两个数是算法自己算的，不是按你的评分换算。" + gap
}

/** 匹配度「对你口味」的门槛（和判定措辞一致）、「不太对口」的门槛。 */
private const val GAP_HIGH_MATCH = 72
private const val GAP_LOW_MATCH = 55

/** 预计评分在你自己的评分里偏低 / 偏高的程度（标准分，约等于低于 / 高于你看过的四分之三作品）。 */
private const val GAP_SELF = 0.67

/** 社区评分比你的均分低 / 高这么多，就算「预计评分主要被社区分拉低 / 抬高」。 */
private const val GAP_COMMUNITY = 1.0

/** 归因里只列影响至少这么大的部分。 */
private const val GAP_FACTOR = 0.2

/**
 * 匹配度和预计评分明显不一致时的说明：口味很对但预计分偏低，或口味不太对但预计分偏高。
 * 只列模型里真实存在的依据（社区评分、评分模型里影响最大的部分、是不是你常看的那类），说清两边依据矛盾、把握不大；
 * 不编造「你喜欢设定但嫌剧情差」这类原因。两者一致时为空。
 */
internal fun disagreementNote(match: Int, p: RatingPrediction, circleZ: Double?): String {
    val self = p.relativeToSelf
    return when {
        match >= GAP_HIGH_MATCH && self <= -GAP_SELF -> buildString {
            append("两个数不一致：匹配度说题材对你口味")
            if (circleZ != null && circleZ >= 1.0) append("（这部是你常看的那类）")
            append("，预计评分却在你看过的作品里偏低。")
            p.community?.takeIf { it <= p.userMean - GAP_COMMUNITY }?.let {
                append("预计评分主要跟着社区评分走，这部社区评分只有 ${"%.1f".format(it)}。")
            }
            p.factors.filter { it.delta <= -GAP_FACTOR }.take(2).takeIf { it.isNotEmpty() }?.let { fs ->
                append("评分模型里拉低它的：${fs.joinToString("、") { "${it.label} ${"%+.1f".format(it.delta)}" }}。")
            }
            append("两边的依据互相矛盾，这部的结果把握不大，可以看下方「可能打几分」。")
        }
        match < GAP_LOW_MATCH && self >= GAP_SELF -> buildString {
            append("两个数不一致：不太是你常看的口味，预计评分却在你看过的作品里偏高。")
            p.community?.takeIf { it >= p.userMean + GAP_COMMUNITY }?.let {
                append("预计评分主要跟着社区评分走，这部社区评分有 ${"%.1f".format(it)}。")
            }
            p.factors.filter { it.delta >= GAP_FACTOR }.take(2).takeIf { it.isNotEmpty() }?.let { fs ->
                append("评分模型里抬高它的：${fs.joinToString("、") { "${it.label} ${"%+.1f".format(it.delta)}" }}。")
            }
        }
        else -> ""
    }
}

/** 置信度单独成一句文案，不塞进理由长句。 */
internal fun confidenceLabel(confidence: Double): String = when {
    confidence < LOW_CONFIDENCE -> "置信度偏低（样本较少，仅供参考）"
    confidence < MID_CONFIDENCE -> "置信度中等"
    else -> "置信度较高"
}

/** 判定措辞：回测还没算完时说明这一点；样本少时注明低置信。 */
internal fun qualitativeLabel(pending: Boolean, lowConfidence: Boolean, qualitative: String): String = when {
    pending -> "回测还没算完"
    lowConfidence -> "$qualitative（低置信）"
    else -> qualitative
}

/** 置信度低于这个值算「偏低」（样本较少），低于 [MID_CONFIDENCE] 算「中等」。 */
internal const val LOW_CONFIDENCE = 0.4
private const val MID_CONFIDENCE = 0.7
