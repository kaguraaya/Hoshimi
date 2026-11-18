package com.acgcompass.domain.model

/**
 * AI / 规则任务类型。包含 [UNKNOWN] 以兜底未知持久化值。
 */
enum class AiTaskType {
    SPOILER_RADAR,
    TASTE_PROFILE,
    RECOMMENDER,
    ROUTE_MAP,
    TASTE_MATCH,

    /** 社区标签 → 口味维度（[com.acgcompass.domain.taste.TasteCategory]）批量分类，结果缓存供画像/评分复用。 */
    TAG_CLASSIFY,
    VNDB_TRANSLATION,

    /** 首页「今日补番签」：结合待补池生成当天的宜 / 忌。 */
    DAILY_DRAW,
    UNKNOWN,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 回退为 [UNKNOWN]。 */
        fun fromStorage(raw: String?): AiTaskType = entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/**
 * 结果生成方：AI 生成 vs 本地规则生成。无 AI key 时使用 [RULE] 回退。
 */
enum class AiGenerator {
    AI,
    RULE,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 回退为 [RULE]（保守地视作本地规则）。 */
        fun fromStorage(raw: String?): AiGenerator = entries.firstOrNull { it.name == raw } ?: RULE
    }
}

/**
 * 缓存的 AI / 规则结果。
 *
 * - [payloadJson]：结构化输出（固定 schema）的 JSON 文本。
 * - [confidence]：置信度；低置信不编造。
 * - [dataSources]：贡献来源标签列表。
 * - [generator]：AI / RULE 标注。
 */
data class AiResult(
    val id: String,
    val workId: String,
    val taskType: AiTaskType,
    val generator: AiGenerator,
    val payloadJson: String,
    val confidence: Float,
    val dataSources: List<String> = emptyList(),
    val generatedAt: Long,
    val inputFingerprint: String? = null,
    val promptVersion: Int = 1,
)
