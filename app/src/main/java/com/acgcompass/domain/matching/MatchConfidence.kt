package com.acgcompass.domain.matching

/**
 * 多源合并阈值。
 *
 * 当某候选条目的 [matchConfidence] **大于等于** 该阈值时，匹配器（[decideMatch]）判定为可自动合并到
 * 同一规范化 `Work`；否则标记为「待用户确认」，需要用户在导入 / 搜索时手动选择正确条目。
 *
 * 取值 `0.85`，与设计文档「高于阈值（如 0.85）自动合并」一致。集中定义在此，便于全局调参与测试引用。
 */
public const val MATCH_THRESHOLD: Double = 0.85

/**
 * 综合匹配置信度。纯函数，可单元/属性测试。
 *
 * 把 **标题相似度**（[similarity]）与可选的 **年份一致性**、**类型一致性** 组合为一个 `[0,1]` 的置信度。
 * 采用加权平均：
 * - 标题权重 `0.70`（始终参与）。
 * - 年份一致性权重 `0.15`，类型一致性权重 `0.15`，仅当对应信号 **已知**（非 `null`）时参与。
 *
 * 当某信号未知（`null`，例如某源没有年份字段）时，其权重不计入，剩余权重按比例放大——
 * 即「缺失信号既不加分也不扣分」，符合「不伪造数据、缺失即忽略」的原则。
 * 当信号为 `false`（年份/类型不一致）时拉低置信度；为 `true` 时拉高。
 *
 * @param candidate 候选条目标题（任意语言名 / 别名均可，内部会归一化）。
 * @param query     查询标题。
 * @param yearMatch 年份是否一致：`true` 一致 / `false` 不一致 / `null` 未知或不比较。
 * @param typeMatch 媒介类型是否一致：`true` / `false` / `null` 未知或不比较。
 * @return 置信度 ∈ `[0,1]`。
 */
public fun matchConfidence(
    candidate: String,
    query: String,
    yearMatch: Boolean? = null,
    typeMatch: Boolean? = null,
): Double {
    val titleWeight = 0.70
    val yearWeight = 0.15
    val typeWeight = 0.15

    val titleSim = similarity(candidate, query)

    var weightedSum = titleSim * titleWeight
    var totalWeight = titleWeight

    if (yearMatch != null) {
        weightedSum += (if (yearMatch) 1.0 else 0.0) * yearWeight
        totalWeight += yearWeight
    }
    if (typeMatch != null) {
        weightedSum += (if (typeMatch) 1.0 else 0.0) * typeWeight
        totalWeight += typeWeight
    }

    return (weightedSum / totalWeight).coerceIn(0.0, 1.0)
}
