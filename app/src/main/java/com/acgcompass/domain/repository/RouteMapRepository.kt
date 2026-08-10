package com.acgcompass.domain.repository

import com.acgcompass.domain.model.RouteNode
import kotlinx.coroutines.flow.Flow

/**
 * 补番路线图仓库契约（领域层，纯 Kotlin）。负责某系列关联作品（节点）的观察、写入，
 * 以及「一键加入系列」到待补池的数据通路。
 *
 * **职责边界**：本契约只覆盖**展示 + 加入待补池**的数据路径。
 * 推荐观看顺序的计算与「路线待确认」判定属于
 * 此处仅以 [RouteNode.orderIndex] / [RouteNode.confirmed] 作为存储钩子持久化，**不**在本层编造顺序。
 *
 * 单一可信源为本地缓存（Room）；[observeRoute] 始终从本地 Flow 读取，离线可用、状态一致
 * （本地优先）。
 *
 */
interface RouteMapRepository {

    /**
     * 观察某系列的全部路线节点。按 [RouteNode.orderIndex] 升序发射；
     * 系列无节点时发射空列表（由调用方决定空态展示）。
     *
     * @param seriesId 系列标识。
     * @return 路线节点列表流。
     */
    fun observeRoute(seriesId: String): Flow<List<RouteNode>>

    /**
     * 写入 / 更新一组路线节点（按 [RouteNode.id] upsert）。供数据源同步关联作品时落库
     * （展示数据来源）。本方法仅持久化，不计算顺序（顺序逻辑见 ）。
     *
     * @param nodes 待写入的节点集合。
     */
    suspend fun upsertNodes(nodes: List<RouteNode>)

    /**
     * 一键将系列加入待补池。
     *
     * 将系列下节点对应的、本地已存在的作品批量加入待补池，复用 [BacklogRepository.addAll] 的
     * 去重与被安利计数语义（幂等）。
     *
     * @param seriesId 系列标识。
     * @param mustOnly `true` 仅加入「必看」（[com.acgcompass.domain.ai.RouteRecommendation.MUST]）节点；
     *                 `false` 加入整个系列的全部节点。
     * @return [AddResult]：新增与去重的作品 id 明细。
     */
    suspend fun addSeriesToBacklog(seriesId: String, mustOnly: Boolean): AddResult
}
