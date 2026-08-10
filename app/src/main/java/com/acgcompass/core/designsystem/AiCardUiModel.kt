package com.acgcompass.core.designsystem

/**
 * AI 卡片 [AiCard] 的 UI 数据模型。
 *
 * AI 卡片需向用户透明地标注结果的来源与可信度，避免「AI 黑箱」误导：是 AI 生成还是规则生成、
 * 何时生成、基于哪些数据、置信度如何，并提供重新生成入口。
 *
 * @property generator        结果来源：[Generator.AI]（AI 生成）或 [Generator.RULE]（规则生成 / 本地回退）。
 * @property generatedAtText  生成时间展示文案（已由上层格式化为本地化字符串）。
 * @property sources          数据来源列表（如 短评 / Reviews / 标签 / AI）。
 * @property confidence       置信度等级：高 / 中 / 低（低置信不编造）。
 */
data class AiCardUiModel(
    val generator: Generator,
    val generatedAtText: String,
    val sources: List<String> = emptyList(),
    val confidence: Confidence,
) {
    /** 结果生成方式。 */
    enum class Generator { AI, RULE }

    /** 结果置信度等级。 */
    enum class Confidence { HIGH, MEDIUM, LOW }
}
