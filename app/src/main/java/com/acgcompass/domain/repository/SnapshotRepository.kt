package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.ChangeLog
import com.acgcompass.domain.model.Snapshot
import com.acgcompass.domain.model.SnapshotKind
import kotlinx.coroutines.flow.Flow

/**
 * 时光机快照仓库契约（领域层，纯 Kotlin）。负责快照的观察 / 记录与变更日志的观察。
 *
 * 首次导入建立 [SnapshotKind.INITIAL]，后续同步建立 [SnapshotKind.SYNC] 并与上次快照 diff 生成
 * [ChangeLog]。仅记录从首次同步起的变化，不复刻第三方完整历史。
 *
 */
interface SnapshotRepository {

    /**
     * 观察全部快照，按时间排序。本地数据变化时应重新发射。
     *
     * @return 快照列表流（时间线）。
     */
    fun observeSnapshots(): Flow<List<Snapshot>>

    /**
     * 获取最近一次快照。无任何快照时为 [AppResult.Success] 携带 `null`。
     *
     * @return 成功时为最近快照（可为 `null`）；失败时为 [AppResult.Failure]。
     */
    suspend fun latest(): AppResult<Snapshot?>

    /**
     * 记录一次当前状态的快照。若与上次快照存在差异，应同时生成对应 [ChangeLog]。
     *
     * @param kind 快照类型（首次导入 [SnapshotKind.INITIAL]，后续同步 [SnapshotKind.SYNC]）。
     * @return 成功时为新建的 [Snapshot]；失败时为 [AppResult.Failure]。
     */
    suspend fun recordSnapshot(kind: SnapshotKind): AppResult<Snapshot>

    /**
     * 观察变更日志。可按作品过滤，便于「以前的我怎么想 / 评分打脸现场」。
     *
     * @param workId 仅观察该作品的变更；`null` 表示观察全部变更。
     * @return 变更日志列表流。
     */
    fun observeChangeLog(workId: String? = null): Flow<List<ChangeLog>>
}
