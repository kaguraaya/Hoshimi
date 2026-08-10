package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.Priority
import com.acgcompass.domain.model.Work
import kotlinx.coroutines.flow.Flow

/**
 * 待补池仓库契约（领域层，纯 Kotlin）。负责待补条目的观察、批量加入（去重）、
 * 优先级 / 批量操作与一键抽番。
 *
 */
interface BacklogRepository {

    /**
     * 按过滤与排序观察待补池。条件变化时应重新发射。
     *
     * @param filter 过滤条件；默认 [BacklogFilter.NONE] 不过滤。
     * @param sort 排序方式；默认按加入时间降序。
     */
    fun observeBacklog(
        filter: BacklogFilter = BacklogFilter.NONE,
        sort: BacklogSort = BacklogSort.ADDED_DESC,
    ): Flow<List<BacklogItem>>

    /**
     * 批量加入待补池并去重。重复作品不新增条目，其被安利次数自增
     * 。该操作幂等：对同一集合重复执行不增大池规模。
     *
     * @param items 待加入的作品集合（可含重复）。
     * @return [AddResult]：新增与去重的作品 id 明细。
     */
    suspend fun addAll(items: List<Work>): AddResult

    /**
     * 设置某待补条目的优先级。
     *
     * @param id 作品 id（待补条目主键）。
     * @param p 目标优先级。
     */
    suspend fun setPriority(id: String, p: Priority)

    /**
     * 设置 / 清除某待补条目的自定义备注。
     *
     * @param id 作品 id（待补条目主键）。
     * @param note 备注内容；`null` 或空串表示清除备注。
     */
    suspend fun setNote(id: String, note: String?)

    /**
     * 对选中条目执行批量操作。
     *
     * @param op 批量操作类型。
     * @param ids 选中的作品 id 列表。
     * @return 成功时为 [AppResult.Success]（Unit）；失败时为 [AppResult.Failure]。
     */
    suspend fun bulk(op: BulkOp, ids: List<String>): AppResult<Unit>

    /**
     * 把作品移入吃灰馆，并保存归档前的 Bangumi 收藏状态 [prevStatus]。
     * 仅置 `inDustMuseum=true` 与 `prevStatus`，不触碰其它字段；作品不在池中则无操作。
     */
    suspend fun archiveToDust(workId: String, prevStatus: String?)

    /**
     * 把作品移出吃灰馆，清除并返回 [archiveToDust] 写入的归档前状态。
     * 置 `inDustMuseum=false`、`prevStatus=null`；作品不在池中或无记录返回 `null`。
     */
    suspend fun restoreFromDust(workId: String): String?

    /**
     * 一键抽番，返回满足硬过滤约束的条目及可解释理由。
     * 无满足约束的候选时 [DrawResult.pick] 为 `null`。
     *
     * @param criteria 抽番硬过滤约束（时间 / 心情 / 风险 / 期末保护 / 深夜等）。
     * @return 抽番结果（含理由）。
     */
    suspend fun draw(criteria: DrawCriteria): DrawResult
}
