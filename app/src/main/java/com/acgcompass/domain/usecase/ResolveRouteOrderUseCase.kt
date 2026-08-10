package com.acgcompass.domain.usecase

import com.acgcompass.domain.model.RouteNode

/**
 * 补番路线推荐观看顺序的解析结果。
 *
 * @property orderedNodes 推荐观看顺序排列后的节点。当 [routeConfirmed] 为 `false` 时，保持输入的
 *   原始相对顺序，**不**据 [RouteNode.orderIndex] 强行重排（不编造顺序）。
 * @property routeConfirmed 路线是否已确认：仅当所有节点都已确认（[RouteNode.confirmed]）且其
 *   [RouteNode.orderIndex] 构成一组互不相同的有效序号时为 `true`；否则为 `false`（「路线待确认」）。
 */
data class RouteResolution(
    val orderedNodes: List<RouteNode>,
    val routeConfirmed: Boolean,
)

/**
 * 推荐观看顺序与「路线待确认」解析器（领域用例，纯 Kotlin、无 Android / IO 依赖）。
 *
 * 「绝不编造顺序」约束：
 * - 当任一节点 [RouteNode.confirmed] 为 `false`，或节点的 [RouteNode.orderIndex] 存在重复 / 无法
 *   构成确定顺序时，判定为「路线待确认」（[RouteResolution.routeConfirmed] = `false`），此时**保持**
 *   输入的原始相对顺序，不依据 orderIndex 重排，避免给用户误导性的「确定顺序」。
 * - 仅当全部节点已确认且 orderIndex 互不相同时，才按 orderIndex 升序给出推荐观看顺序。
 *
 */
class ResolveRouteOrderUseCase {

    /**
     * 解析一组路线节点的推荐观看顺序与确认状态。
     *
     * @param nodes 某系列的全部路线节点（来自 `RouteMapRepository.observeRoute`）。
     */
    operator fun invoke(nodes: List<RouteNode>): RouteResolution {
        if (nodes.isEmpty()) {
            return RouteResolution(orderedNodes = emptyList(), routeConfirmed = false)
        }
        val allConfirmed = nodes.all { it.confirmed }
        val indices = nodes.map { it.orderIndex }
        val distinctIndices = indices.toSet().size == indices.size
        val confirmed = allConfirmed && distinctIndices
        val ordered = if (confirmed) nodes.sortedBy { it.orderIndex } else nodes
        return RouteResolution(orderedNodes = ordered, routeConfirmed = confirmed)
    }
}
