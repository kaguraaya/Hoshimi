package com.acgcompass.domain.funfeature

import com.acgcompass.domain.model.TasteProfile

/**
 * 补番人格输入信号。从口味画像中抽取的与数据源无关的纯信号，便于纯函数推导称号。
 *
 * - [avgScore]：平均分（10 分制）。
 * - [strictness]：评分严格度（0–1，越大越严苛）。
 * - [highScoreRarity]：高分稀有度（0–1，越大越吝啬给高分）。
 * - [topTags]：高分倾向的代表标签（可空）。
 */
data class TasteSignals(
    val avgScore: Float,
    val strictness: Float,
    val highScoreRarity: Float,
    val topTags: List<String> = emptyList(),
)

/**
 * 补番人格称号——从口味画像生成。
 *
 * 优先复用口味画像已计算出的称号 [TasteProfile.titles]；若为空，则退回到基于信号的
 * 占位规则 [bingePersona]，保证总能给出一个非空称号（绝不伪造，但提供有趣的兜底）。
 */
fun bingePersona(profile: TasteProfile): String {
    val title = profile.titles.firstOrNull { it.isNotBlank() }
    if (title != null) return title
    return bingePersona(
        TasteSignals(
            avgScore = profile.avgScore,
            strictness = profile.strictness,
            highScoreRarity = profile.highScoreRarity,
        ),
    )
}

/**
 * 补番人格称号——基于口味信号的占位规则（纯函数，确定性）。
 *
 * 规则按优先级匹配，保证任何输入都返回非空称号：
 * 1. 严格度高 → 严苛评审；
 * 2. 高分稀有度高 → 高分守门员；
 * 3. 平均分高 → 高分博爱党；
 * 4. 平均分偏低 → 阅片无数的毒舌；
 * 5. 其余 → 随缘佛系观众。
 */
fun bingePersona(signals: TasteSignals): String = when {
    signals.strictness >= STRICTNESS_HIGH -> "嘴上嫌弃身体诚实的严苛评审"
    signals.highScoreRarity >= HIGH_SCORE_RARITY_HIGH -> "惜分如金的高分守门员"
    signals.avgScore >= AVG_SCORE_HIGH -> "雨露均沾的高分博爱党"
    signals.avgScore <= AVG_SCORE_LOW -> "阅片无数的毒舌鉴赏家"
    else -> "随缘补番的佛系观众"
}

/** 严格度高阈值（含）。 */
const val STRICTNESS_HIGH: Float = 0.7f

/** 高分稀有度高阈值（含）。 */
const val HIGH_SCORE_RARITY_HIGH: Float = 0.7f

/** 平均分高阈值（含，10 分制）。 */
const val AVG_SCORE_HIGH: Float = 8.0f

/** 平均分低阈值（含，10 分制）。 */
const val AVG_SCORE_LOW: Float = 5.0f
