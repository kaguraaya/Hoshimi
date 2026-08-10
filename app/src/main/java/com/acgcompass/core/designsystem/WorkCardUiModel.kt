package com.acgcompass.core.designsystem

/**
 * 统一作品卡片 [WorkCard] 的 UI 数据模型。
 *
 * 这是一个纯展示用的 UI 模型，已由上层（ViewModel / mapper）把领域模型（`Work` +
 * `RatingAggregate` + `BacklogItem` 等）折叠成可直接渲染的字符串/列表，使设计系统组件保持与
 * 领域层解耦、可独立预览与快照测试。
 *
 * 设计要点（缺失即标记，不伪造 ——）：
 * - [coverUrl] 为 `null` 时由 [WorkCard] 渲染占位封面，不留空洞。
 * - [ratingText] 为 `null` 时由 [WorkCard] 显示「暂无数据」而非空白或 0。
 * - 长标题由 [WorkCard] 通过 `maxLines + ellipsis` 兜底。
 *
 * @property coverUrl     封面图地址；`null` 表示缺失，渲染占位。
 * @property title        主标题（规范化标题）。可能很长，渲染时做截断兜底。
 * @property subtitle     副标题，通常为「别名 / 年份」组合（已由上层拼接）。
 * @property type         作品类型展示文案（动画 / 漫画 / 小说 / 游戏 / VN 等）。
 * @property ratingText   评分展示文案（可含多源/聚合）；`null` => 显示「暂无数据」。
 * @property sourceTags   来源标签（如 Bangumi / AniList），用于标注数据来源。
 * @property backlogStatus 待补状态文案（如「想看」「在看」「吃灰 30 天」）；`null` 则不展示。
 * @property completionCost 补完成本分桶文案（今晚 / 周末 / 长期坑）；`null` 则不展示。
 * @property moodRiskTags 风险 / 心情标签集合，渲染为一组 chips。
 */
data class WorkCardUiModel(
    val coverUrl: String?,
    val title: String,
    val subtitle: String,
    val type: String,
    val ratingText: String?,
    val sourceTags: List<String> = emptyList(),
    val backlogStatus: String? = null,
    val completionCost: String? = null,
    val moodRiskTags: List<String> = emptyList(),
)
