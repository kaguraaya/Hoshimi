package com.acgcompass.feature.detail

import com.acgcompass.domain.taste.TasteMatchResult

// 详情页口味区的两句补充说明：已评分作品的回测值，以及匹配度和预计评分差得多时的解释。

/** 已评分作品的回测说明。 */
internal fun TasteMatchResult.backtestLine(): String {
    val bt = backtest ?: return "回测计算中：稍后显示「建画像时不知道这部作品」算出的匹配度和预计评分；" +
        "上面暂时是把这部作品算进画像后的匹配度，会偏高一些"
    val match = bt.match?.let { "匹配度 $it%" } ?: "匹配度暂无"
    val predicted = bt.predicted?.let { " · 预计你会打 ${"%.1f".format(it)} 分" }.orEmpty()
    return "回测（建画像时不知道这部作品）：$match$predicted · 你的评分 ${bt.rating} 分。" +
        "这两个数是算法自己算的，不是按你的评分换算"
}

/** 匹配度和预计评分「明显不一致」的门槛：匹配度高 / 低到这里，预计评分又在你的均分另一侧。 */
private const val GAP_HIGH_MATCH = 80
private const val GAP_LOW_MATCH = 55
private const val GAP_RATING = 0.5

/**
 * 两个数字差得多时的解释：口味很对但预计分不高，或口味不太对但预计分不低。只用模型里有的依据，不编造原因。
 */
internal fun TasteMatchResult.gapNote(rating: Double, userMean: Double): String = when {
    score >= GAP_HIGH_MATCH && rating < userMean - GAP_RATING ->
        "口味上很对你，但同类作品里这部的口碑和你给相近作品的分都不算高，所以预计分不高。"
    score < GAP_LOW_MATCH && rating > userMean + GAP_RATING ->
        "不太是你常看的口味，但口碑不错，按你的打分习惯，看了可能会给不低的分。"
    else -> ""
}
