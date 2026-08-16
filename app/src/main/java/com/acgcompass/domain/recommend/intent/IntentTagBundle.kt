package com.acgcompass.domain.recommend.intent

/** 意图所属的层面；情绪 / 基调 / 结构类意图在长篇作品上会被稀释（见 [TagRepresentativeness]）。 */
enum class IntentCategory { TOPIC, SETTING, RELATION, EMOTION, TONE, STRUCTURE }

/**
 * 「今晚想看的标签」对应的意图包：用户点的是一个词，要表达的是一类作品。
 *
 * @property requiredTags 直接等价的标签（命中即按原代表性计）。
 * @property synonymTags 近义标签（按 0.85 折算）。
 * @property weakTags 只是沾边的标签（按 0.45 折算，单靠它们拿不到满分）。
 * @property conflictTags 与该意图主气质冲突的标签——作品以它们为主时，不算这类作品。
 * @property representativeThreshold 代表性达到多少算「这部作品确实是这一类」。
 * @property missingPenalty 该意图缺失时的额外惩罚；仅 [isKey] 意图生效。
 * @property isKey 关键意图：选了却完全没有，不能靠别的标签或高口味分补回来。
 */
data class IntentTagBundle(
    val id: String,
    val category: IntentCategory,
    val requiredTags: Set<String>,
    val synonymTags: Set<String> = emptySet(),
    val weakTags: Set<String> = emptySet(),
    val conflictTags: Set<String> = emptySet(),
    val representativeThreshold: Double = DEFAULT_THRESHOLD,
    val missingPenalty: Double = DEFAULT_MISSING_PENALTY,
    val isKey: Boolean = false,
) {
    companion object {
        const val DEFAULT_THRESHOLD: Double = 0.30
        const val DEFAULT_MISSING_PENALTY: Double = 0.20
    }
}

/**
 * 常见组合意图模板：几个标签一起选时，用户要的是一种完整的氛围。
 *
 * @property requiredGroups 必须同时被用户选中、且作品都达标的意图 id。
 * @property optionalGroups 至少一项达标即可的意图 id（空 = 不要求）。
 * @property conflictTags 主气质冲突标签；作品以它们为主时扣回 [conflictPenalty]。
 */
data class IntentComboTemplate(
    val id: String,
    val displayName: String,
    val requiredGroups: Map<String, Double>,
    val optionalGroups: Set<String>,
    val optionalMinSat: Double,
    val conflictTags: Set<String>,
    val bonus: Double,
    val conflictPenalty: Double,
)
