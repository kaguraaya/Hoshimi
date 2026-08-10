package com.acgcompass.domain.model

/**
 * 口味画像中某标签的统计桶：高分倾向 / 低分倾向。
 * 包含 [UNKNOWN] 以兜底未知持久化值。
 */
enum class TagBucket {
    HIGH_SCORE,
    LOW_SCORE,
    UNKNOWN,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 回退为 [UNKNOWN]。 */
        fun fromStorage(raw: String?): TagBucket = entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/**
 * 口味画像中的单条标签统计。[count] 为该标签在对应 [bucket] 中的出现频次。
 */
data class TasteTagStat(
    val tagName: String,
    val bucket: TagBucket,
    val count: Int,
)

/**
 * 计算得到的口味画像（口味画像）。
 *
 * - [strictness]：评分严格度。
 * - [avgScore]：平均分。
 * - [highScoreRarity]：高分稀有度。
 * - [commonScoreBand]：常见分段（可缺失）。
 * - [titles]：补番人格称号等。
 * - [confidence]：置信度；样本不足时为低置信，措辞采用「可能 / 倾向于」。
 * - [tagStats]：按标签桶的统计明细。
 * - [blackHole]：口味黑洞——最常被搁置 / 抛弃的类型；无数据时为空，不伪造。
 */
data class TasteProfile(
    val id: String,
    val strictness: Float,
    val avgScore: Float,
    val highScoreRarity: Float,
    val commonScoreBand: String? = null,
    val titles: List<String> = emptyList(),
    val confidence: Float,
    val generatedAt: Long,
    val tagStats: List<TasteTagStat> = emptyList(),
    val blackHole: List<String> = emptyList(),
)
