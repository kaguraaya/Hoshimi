package com.acgcompass.domain.usecase

/**
 * 口味导入与统计的单条输入记录（口味画像）。来自 Bangumi 等源的用户个人数据，
 * 已被归一为与具体数据源无关的纯领域结构，便于纯函数统计与测试：
 *
 * - [rating]：用户个人评分（典型 1–10，`null` 表示未评分，不参与高/低分桶）。
 * - [tags]：该作品的标签（同一记录内重复标签视为一次，避免单作品多标签放大计数）。
 * - [reviewText]：用户短评原文（`null`/空白时不参与常用短评词统计）。
 * - [status]：收藏状态（如 `dropped`/`on_hold`/`抛弃`/`搁置`），用于统计常见搁置类型。
 * - [updatedAt]：最近更新时间戳（毫秒），用于近因加权；`null` 按中性权重处理。
 */
data class TasteInputRecord(
    val rating: Int? = null,
    val tags: List<String> = emptyList(),
    val reviewText: String? = null,
    val status: String? = null,
    val updatedAt: Long? = null,
)

/** 某个名称（标签 / 短评词 / 搁置类型）的出现计数。[count] 恒为非负。 */
data class NameCount(
    val name: String,
    val count: Int,
)

/**
 * [TasteStatsCalculator] 的纯统计输出。除标签 / 词频明细外，额外携带各「桶」的
 * **样本计数**，使「统计守恒」可被验证：
 *
 * - 任意桶的样本计数与单项计数均 `>= 0`；
 * - [highScoreSampleCount] + [lowScoreSampleCount] `<=` [sampleSize]（高/低分阈值不重叠，
 *   单条记录至多落入一个分数桶）；
 * - 桶内任一标签的计数 `<=` 该桶样本计数 `<=` [sampleSize]（单作品内标签去重）。
 */
data class TasteStats(
    val sampleSize: Int,
    val highScoreSampleCount: Int,
    val lowScoreSampleCount: Int,
    val droppedSampleCount: Int,
    val highScoreTags: List<NameCount>,
    val lowScoreTags: List<NameCount>,
    val commonReviewWords: List<NameCount>,
    val droppedTypes: List<NameCount>,
)

/**
 * 口味统计计算器（领域用例，纯 Kotlin、无 Android / IO 依赖）。
 *
 * 给定一组 [TasteInputRecord]，按分数阈值把记录划入高分 / 低分桶，分别统计标签频次；同时统计
 * 用户短评的常用词，以及常见的搁置 / 抛弃类型。设计为纯函数，便于单元测试与
 * （口味统计守恒）的属性测试。
 *
 * 守恒保证：
 * - 单条记录内标签先去重，故任一标签在某桶中的计数不超过该桶样本数；
 * - 高/低分阈值不重叠（[LOW_SCORE_MAX] < [HIGH_SCORE_MIN]），故一条记录不会同时计入两个分数桶，
 *   高分样本数 + 低分样本数不超过样本总数；
 * - 所有计数均为非负。
 *
 */
class TasteStatsCalculator {

    /**
     * 对 [records] 做统计。结果列表均按计数降序、名称升序排序，便于稳定展示与断言。
     */
    operator fun invoke(records: List<TasteInputRecord>): TasteStats {
        val highRecords = records.filter { it.rating != null && it.rating >= HIGH_SCORE_MIN }
        val lowRecords = records.filter { it.rating != null && it.rating <= LOW_SCORE_MAX }
        val droppedRecords = records.filter { isDroppedStatus(it.status) }
        // 以最近一条记录为基准计算近因权重，使近期评分对高低分标签
        // 排序的影响更大；无任何时间戳时基准为 0 → 全员权重 1.0 → 行为与未加权完全一致（向后兼容）。
        val reference = records.maxOfOrNull { it.updatedAt ?: 0L } ?: 0L

        return TasteStats(
            sampleSize = records.size,
            highScoreSampleCount = highRecords.size,
            lowScoreSampleCount = lowRecords.size,
            droppedSampleCount = droppedRecords.size,
            highScoreTags = tallyTags(highRecords, reference),
            lowScoreTags = tallyTags(lowRecords, reference),
            commonReviewWords = tallyReviewWords(records, reference),
            droppedTypes = tallyTags(droppedRecords, reference),
        )
    }

    /**
     * 统计一批记录的标签：单作品内标签去重并规范化，空白标签忽略。
     *
     * 展示计数（[NameCount.count]）仍为**原始出现作品数**（直观、可守恒），
     * 列表排序使用近因加权得分（[recencyWeight]），使近期评分的标签优先靠前。
     */
    private fun tallyTags(records: List<TasteInputRecord>, reference: Long): List<NameCount> {
        val rawCount = LinkedHashMap<String, Int>()
        val weighted = LinkedHashMap<String, Double>()
        for (record in records) {
            val w = recencyWeight(record.updatedAt, reference)
            val seen = record.tags
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            for (tag in seen) {
                rawCount[tag] = (rawCount[tag] ?: 0) + 1
                weighted[tag] = (weighted[tag] ?: 0.0) + w
            }
        }
        return buildNameCounts(rawCount, weighted)
    }

    /** 统计常用短评词：分词、规范化、过滤停用词与过短 token，再按近因加权频次排序。 */
    private fun tallyReviewWords(records: List<TasteInputRecord>, reference: Long): List<NameCount> {
        val rawCount = LinkedHashMap<String, Int>()
        val weighted = LinkedHashMap<String, Double>()
        for (record in records) {
            val text = record.reviewText ?: continue
            val w = recencyWeight(record.updatedAt, reference)
            for (token in tokenize(text)) {
                rawCount[token] = (rawCount[token] ?: 0) + 1
                weighted[token] = (weighted[token] ?: 0.0) + w
            }
        }
        return buildNameCounts(rawCount, weighted)
    }

    /**
     * 简易分词：按非字母/数字/CJK 字符切分，转小写，丢弃长度过短的 token 与停用词。
     * 不依赖任何外部 NLP 库，保持纯函数与可测试性。
     */
    private fun tokenize(text: String): List<String> = text.lowercase()
        .split(TOKEN_DELIMITERS)
        .map { it.trim() }
        .filter { it.length >= MIN_WORD_LENGTH && it !in STOP_WORDS }

    private fun isDroppedStatus(status: String?): Boolean {
        val normalized = status?.trim()?.lowercase() ?: return false
        return normalized in DROPPED_STATUSES
    }

    /**
     * 以 [reference]（最近一条记录的时间戳）为基准，按半衰期 [RECENCY_HALF_LIFE_MILLIS]
     * 指数衰减，并设权重地板 [RECENCY_MIN_WEIGHT]（旧评分仍保留基本话语权，不会归零）。
     *
     * 无时间戳（`updatedAt == null`）或无基准（`reference <= 0`）时返回 1.0（中性，等价未加权）。
     */
    private fun recencyWeight(updatedAt: Long?, reference: Long): Double {
        if (reference <= 0L || updatedAt == null) return 1.0
        val age = (reference - updatedAt).coerceAtLeast(0L)
        if (age == 0L) return 1.0
        val halfLives = age.toDouble() / RECENCY_HALF_LIFE_MILLIS
        return Math.pow(0.5, halfLives).coerceAtLeast(RECENCY_MIN_WEIGHT)
    }

    /** 按近因加权得分降序、名称升序排序；计数取原始出现次数（直观且满足守恒）。 */
    private fun buildNameCounts(rawCount: Map<String, Int>, weighted: Map<String, Double>): List<NameCount> =
        rawCount.keys
            .sortedWith(compareByDescending<String> { weighted[it] ?: 0.0 }.thenBy { it })
            .map { NameCount(it, rawCount[it] ?: 0) }

    companion object {
        /** 高分桶下界（含）：评分 `>=` 此值视为高分作品（10 分制）。 */
        const val HIGH_SCORE_MIN: Int = 8

        /** 低分桶上界（含）：评分 `<=` 此值视为低分作品（10 分制）。低于高分下界，故两桶不重叠。 */
        const val LOW_SCORE_MAX: Int = 4

        /** 参与短评词统计的最小 token 长度，过滤无意义的单字符噪声。 */
        const val MIN_WORD_LENGTH: Int = 2

        /** 近因权重半衰期约为 540 天；每过一个半衰期权重减半。 */
        const val RECENCY_HALF_LIFE_MILLIS: Long = 540L * 24 * 3600 * 1000

        /** 近因权重下限，避免历史口味被完全抹除。 */
        const val RECENCY_MIN_WEIGHT: Double = 0.3

        /** 视为「搁置 / 抛弃」的收藏状态（规范化为小写后比较）。 */
        val DROPPED_STATUSES: Set<String> =
            setOf("dropped", "on_hold", "onhold", "on hold", "搁置", "抛弃", "弃坑")

        /** 短评分词的分隔符：非字母/数字/中日韩统一表意文字一律视为分隔。 */
        private val TOKEN_DELIMITERS: Regex =
            Regex("[^\\p{L}\\p{Nd}]+")

        /** 极常见、无区分度的停用词（中英文混合，最小集合）。 */
        val STOP_WORDS: Set<String> = setOf(
            "the", "and", "for", "but", "not", "was", "are", "you", "this", "that", "with",
            "的", "了", "是", "在", "和", "也", "都", "就", "很", "我", "你", "他", "它",
        )
    }
}
