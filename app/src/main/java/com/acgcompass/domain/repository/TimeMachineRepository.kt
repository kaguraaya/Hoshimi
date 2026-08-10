package com.acgcompass.domain.repository

import com.acgcompass.domain.model.ChangeLog
import com.acgcompass.domain.model.CollectionState
import com.acgcompass.domain.model.Snapshot
import kotlinx.coroutines.flow.Flow

/**
 * 本地时光机仓库契约（领域层，纯 Kotlin）。
 *
 * 职责：在本地维护收藏 / 评分 / 短评 / 进度的历史快照，并在后续同步时与上次快照 diff 生成
 * 变更日志，支撑时间线 / 报告。
 *
 * 规则：
 * - 首次导入 → 建立初始快照 [com.acgcompass.domain.model.SnapshotKind.INITIAL]，仅捕获当前状态，
 *   **不**产生变更记录。
 * - 后续同步 → 与上次快照 diff，生成 新增收藏 / 状态变化 / 评分变化 / 短评变化 / 进度变化
 *   的 [ChangeLog]。
 * - 仅记录从首次同步起的变化，不复刻第三方完整历史。
 *
 */
interface TimeMachineRepository {

    /**
     * 首次导入时捕获初始快照。记录当前 [items] 的完整状态作为基线，不生成变更记录。
     *
     * 幂等性说明：若已存在快照，仍会写入一个新的初始快照状态；调用方应仅在首次导入时调用。
     *
     * @param items 当前收藏 / 评分 / 短评 / 进度状态。
     */
    suspend fun captureInitialSnapshot(items: List<CollectionState>)

    /**
     * 后续同步：与最近一次快照 diff，生成并持久化变更日志。
     *
     * @param newItems 本次同步后的收藏 / 评分 / 短评 / 进度状态。
     * @return 本次同步产生的变更日志列表（无变化时为空列表）。
     */
    suspend fun recordSyncDiff(newItems: List<CollectionState>): List<ChangeLog>

    /**
     * 观察全部变更日志（按时间倒序），用于时间线 / 报告。
     * 仅包含真正的变化（不含初始基线 / 内部状态行）。
     */
    fun observeChangeLogs(): Flow<List<ChangeLog>>

    /** 观察全部快照（按时间倒序），用于时间线。 */
    fun observeSnapshots(): Flow<List<Snapshot>>
}
