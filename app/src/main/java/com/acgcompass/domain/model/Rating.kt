package com.acgcompass.domain.model

/**
 * 单源评分条目。[rank] 可缺失（部分源无排名）。
 * 缺失的源在 [RatingAggregate.perSource] 中以 `null` 表示，**绝不**用其它源数据回填
 * 。
 */
data class RatingEntry(
    val score: Float,
    val voteCount: Int,
    val rank: Int? = null,
)

/**
 * 社区共识。保持简单字段；当有效样本不足以判定时，整个
 * [RatingAggregate.consensus] 为 `null`，而不是给出确定结论。
 *
 * - [stability]：评分稳定度 [0,1]（分歧越小越高）。
 * - [controversy]：争议度 [0,1]（分歧越大越高）。
 * - [priority]：优先级权重 [0,1]，供推荐 / 排序参考。
 */
data class Consensus(
    val stability: Float,
    val controversy: Float,
    val priority: Float,
)

/**
 * 多源评分聚合。
 *
 * [perSource] 以 [SourceId] 为键映射到该源评分；值为 `null` 表示该源**缺失 / 暂无数据**
 * （UI 显示「暂无数据」，不伪造）。[consensus] 在样本不足时为 `null` 或低置信。
 */
data class RatingAggregate(
    val perSource: Map<SourceId, RatingEntry?> = emptyMap(),
    val consensus: Consensus? = null,
)
