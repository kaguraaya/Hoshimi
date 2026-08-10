package com.acgcompass.data.repository

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.local.dao.RouteNodeDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.mapper.toDomain
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.domain.ai.RouteRecommendation
import com.acgcompass.domain.model.RouteNode
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.AddResult
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.RouteMapRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [RouteMapRepository] 实现。
 *
 * **单一可信源 = Room**：[observeRoute] 从 [RouteNodeDao] 的 Flow 出发，
 * 离线可用、状态一致。
 *
 * **职责边界**：本实现是「关联作品展示 + 一键加入系列」的数据通路。推荐观看顺序与「路线待确认」
 * 逻辑属于 ——这里仅持久化 [RouteNode.orderIndex] / [RouteNode.confirmed] 作为钩子，
 * **不**计算 / 编造顺序（留待 27.2）。
 *
 * 关键不变式：
 * - [addSeriesToBacklog] 复用 [BacklogRepository.addAll]，因此自动满足去重 + 幂等
 *   与被安利计数；`mustOnly` 仅保留 [RouteRecommendation.MUST] 节点。
 * - 节点引用的作品若本地不存在（尚未拉取 / 缓存），在加入待补池时被安全跳过，不伪造作品数据
 *   。
 */
@Singleton
class RouteMapRepositoryImpl @Inject constructor(
    private val routeNodeDao: RouteNodeDao,
    private val workDao: WorkDao,
    private val backlogRepository: BacklogRepository,
    private val dispatchers: DispatcherProvider,
) : RouteMapRepository {

    override fun observeRoute(seriesId: String): Flow<List<RouteNode>> = routeNodeDao.observeBySeries(seriesId)
        .map { entities -> entities.map { it.toDomain() } }
        .flowOn(dispatchers.io)

    override suspend fun upsertNodes(nodes: List<RouteNode>) {
        withContext(dispatchers.io) {
            routeNodeDao.upsertAll(nodes.map { it.toEntity() })
        }
    }

    override suspend fun addSeriesToBacklog(seriesId: String, mustOnly: Boolean): AddResult =
        withContext(dispatchers.io) {
            val nodes = routeNodeDao.getBySeries(seriesId).map { it.toDomain() }
            val selected = if (mustOnly) {
                nodes.filter { it.recommendation == RouteRecommendation.MUST }
            } else {
                nodes
            }

            // 仅加入本地已有的作品；缺失作品（未缓存）安全跳过，绝不伪造。
            // 保序 + 去重 workId，让 BacklogRepository.addAll 的去重/计数语义生效。
            val works: List<Work> = selected
                .map { it.workId }
                .distinct()
                .mapNotNull { workId -> workDao.getById(workId)?.toDomain() }

            if (works.isEmpty()) {
                AddResult()
            } else {
                backlogRepository.addAll(works)
            }
        }
}
