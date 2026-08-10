package com.acgcompass.domain.repository

import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Priority

/**
 * 待补池查询过滤条件。所有字段为「与（AND）」语义；`null` / 空集合表示该维度不过滤。
 *
 * - [priorities]：仅保留指定优先级；空集合不过滤。
 * - [mediaTypes]：仅保留指定媒介类型；空集合不过滤。
 * - [moodTags] / [riskTags]：要求包含全部指定心情 / 风险标签；空集合不过滤。
 * - [inDustMuseum]：`true` 仅吃灰博物馆条目，`false` 仅非吃灰条目，`null` 不过滤。
 */
data class BacklogFilter(
    val priorities: Set<Priority> = emptySet(),
    val mediaTypes: Set<MediaType> = emptySet(),
    val moodTags: Set<String> = emptySet(),
    val riskTags: Set<String> = emptySet(),
    val inDustMuseum: Boolean? = null,
) {
    companion object {
        /** 不施加任何过滤的默认条件。 */
        val NONE: BacklogFilter = BacklogFilter()
    }
}

/**
 * 待补池排序方式。
 *
 * - [ADDED_DESC] / [ADDED_ASC]：按加入时间降序 / 升序。
 * - [PRIORITY_DESC]：按优先级（高→低）。
 * - [DUST_DAYS_DESC]：按吃灰天数降序（吃灰最久优先）。
 */
enum class BacklogSort {
    ADDED_DESC,
    ADDED_ASC,
    PRIORITY_DESC,
    DUST_DAYS_DESC,
}

/**
 * 批量加入待补池的结果。
 *
 * - [addedWorkIds]：本次新加入的作品 id（已去重，不含原本就在池中的作品）。
 * - [duplicateWorkIds]：本次因已存在（池中已有或同批次重复出现）而被去重跳过的命中（其 `recommendedCount` 自增）。
 *
 * 不变式：每个输入项被归类为「新增」或「去重」之一；同批次重复出现的作品其首次可计入
 * [addedWorkIds]、后续重复计入 [duplicateWorkIds]，故同一 workId 可同时出现在两者中（按项计数）。
 */
data class AddResult(
    val addedWorkIds: List<String> = emptyList(),
    val duplicateWorkIds: List<String> = emptyList(),
) {
    /** 本次实际新增数量。 */
    val addedCount: Int get() = addedWorkIds.size

    /** 本次因已存在而跳过的数量。 */
    val duplicateCount: Int get() = duplicateWorkIds.size
}

/**
 * 待补池批量操作类型。
 *
 * - [DELETE]：删除选中条目。
 * - [ARCHIVE_TO_DUST_MUSEUM] / [RESTORE_FROM_DUST_MUSEUM]：移入 / 移出吃灰博物馆。
 * - [SET_PRIORITY_HIGH] / [SET_PRIORITY_MEDIUM] / [SET_PRIORITY_LOW]：批量设置优先级。
 */
enum class BulkOp {
    DELETE,
    ARCHIVE_TO_DUST_MUSEUM,
    RESTORE_FROM_DUST_MUSEUM,
    SET_PRIORITY_HIGH,
    SET_PRIORITY_MEDIUM,
    SET_PRIORITY_LOW,
}

/**
 * 一键抽番的硬过滤约束。抽取结果必须满足全部硬性条件。
 *
 * - [availableMinutes]：可用时间（分钟）；`null` 表示不限。
 * - [moodTags]：期望心情标签（命中其一即可）；空集合不限。
 * - [riskTolerance]：可接受的风险标签集合；超出此集合的高风险作品被过滤。
 * - [finalsProtection]：期末保护开启时排除长篇 / 致郁 / 高上头 / 未完结。
 * - [lateNight]：深夜提醒模式，影响理由文案与高风险过滤。
 * - [excludeWorkIds]：额外排除的作品 id（如已完成；不重复推荐已完成）。
 */
data class DrawCriteria(
    val availableMinutes: Int? = null,
    val moodTags: Set<String> = emptySet(),
    val riskTolerance: Set<String> = emptySet(),
    val finalsProtection: Boolean = false,
    val lateNight: Boolean = false,
    val excludeWorkIds: Set<String> = emptySet(),
)

/**
 * 一键抽番结果。抽番带可解释理由；无满足条件的候选时 [pick] 为 `null`。
 *
 * - [pick]：抽中的待补条目；`null` 表示无候选满足约束。
 * - [reason]：抽取 / 无候选的可解释理由。
 */
data class DrawResult(
    val pick: BacklogItem?,
    val reason: String,
)
