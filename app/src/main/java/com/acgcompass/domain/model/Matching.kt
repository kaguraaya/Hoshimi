package com.acgcompass.domain.model

/**
 * 外部源条目引用。指向某数据源中的一个具体条目。
 *
 * - [sourceId]：数据源标识。
 * - [sourceItemId]：该源内的条目 id。
 * - [matchConfidence]：与本地规范化 [Work] 的匹配置信度 ∈ [0,1]。
 * - [userOverridden]：用户是否手动纠正过该链接；为 `true` 时后续同步不再自动改写。
 */
data class SourceRef(
    val sourceId: SourceId,
    val sourceItemId: String,
    val matchConfidence: Float = 1f,
    val userOverridden: Boolean = false,
)

/**
 * 搜索 / 匹配结果。
 *
 * - [work]：匹配到的（或候选的）规范化作品。
 * - [matchConfidence]：匹配置信度 ∈ [0,1]；高于阈值自动合并，低于阈值标记为待用户确认。
 * - [sourceTag]：产生该结果的来源标签，用于 UI 旁标来源。
 * - [popularity]：该候选条目的评分人数 / 热度（来源若提供，如 Bangumi `rating.total`；否则 0）。
 *   **仅用于同置信度时的排序 / 选代表 tiebreak**，绝不单独决定是否合并（避免同名小条目
 *   ——PV / 广播剧 / 同人 / 废弃条目——抢占代表导致评分人数显示异常）。
 */
data class WorkMatch(
    val work: Work,
    val matchConfidence: Float,
    val sourceTag: SourceId,
    val popularity: Int = 0,
)
