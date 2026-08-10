package com.acgcompass.domain.model

/**
 * 批量导入来源，持久化为枚举名字符串。
 *
 * - [PASTE]：用户粘贴的推荐清单文本。
 * - [CLIPBOARD]：从剪贴板读取的文本。
 * - [FILE_TXT] / [FILE_CSV]：本地 TXT / CSV 文件。
 * - [OCR]：截图识别（入口预留）。
 */
enum class ImportSource {
    PASTE,
    CLIPBOARD,
    FILE_TXT,
    FILE_CSV,
    OCR,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 返回 `null`（不臆造来源）。 */
        fun fromStorage(raw: String?): ImportSource? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * 单条导入明细的匹配状态。
 *
 * - [MATCHED]：高置信自动匹配到规范化 [Work]，可直接加入待补池。
 * - [NEEDS_CONFIRMATION]：低置信匹配，需用户确认后再加入。
 * - [UNMATCHED]：未找到任何匹配候选（计入批次失败数）。
 * - [ADDED]：已加入待补池。
 */
enum class ImportItemStatus {
    MATCHED,
    NEEDS_CONFIRMATION,
    UNMATCHED,
    ADDED,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 安全回退为 [UNMATCHED]，保证不崩溃。 */
        fun fromStorage(raw: String?): ImportItemStatus = entries.firstOrNull { it.name == raw } ?: UNMATCHED
    }
}

/**
 * 一次批量导入生成的批次记录。
 *
 * 统计语义：
 * - [recognizedCount]：本次识别（解析）出的候选条数。
 * - [successCount]：成功匹配到规范化作品的条数（[ImportItemStatus.MATCHED] / [ImportItemStatus.ADDED]）。
 * - [failureCount]：未找到任何匹配的条数（[ImportItemStatus.UNMATCHED]）。
 *
 * 低置信待确认条目（[ImportItemStatus.NEEDS_CONFIRMATION]）既不计入成功也不计入失败，等待用户确认。
 */
data class ImportBatch(
    val id: String,
    val name: String,
    val createdAt: Long,
    val source: ImportSource?,
    val recognizedCount: Int,
    val successCount: Int,
    val failureCount: Int,
)

/**
 * 导入批次内的一条明细。
 *
 * @property workId 匹配到的规范化作品 id；低置信 / 未匹配时为 `null`，待用户确认后回填。
 * @property matchConfidence 最佳候选的匹配置信度 ∈ [0,1]；未匹配为 0。
 * @property status 当前匹配状态。
 */
data class ImportItem(
    val id: String,
    val batchId: String,
    val rawText: String,
    val parsedTitle: String?,
    val workId: String?,
    val matchConfidence: Float,
    val status: ImportItemStatus,
)
