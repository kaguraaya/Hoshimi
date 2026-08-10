package com.acgcompass.data.repository

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.local.dao.SnapshotDao
import com.acgcompass.data.local.entity.ChangeLogEntity
import com.acgcompass.data.local.entity.SnapshotEntity
import com.acgcompass.domain.model.ChangeLog
import com.acgcompass.domain.model.ChangeType
import com.acgcompass.domain.model.CollectionState
import com.acgcompass.domain.model.Snapshot
import com.acgcompass.domain.model.SnapshotChange
import com.acgcompass.domain.model.SnapshotKind
import com.acgcompass.domain.repository.TimeMachineRepository
import com.acgcompass.domain.usecase.SnapshotDiff
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * [TimeMachineRepository] 实现。
 *
 * **单一可信源 = Room**：所有读取从 [SnapshotDao] 的 Flow 出发。
 *
 * 持久化模型：
 * - 每个快照（INITIAL / SYNC）都额外写入一条**内部状态行**（[STATE_MARKER]）到 `change_logs`，
 *   其 `newValue` 为该快照完整状态的 JSON。后续同步据此重建上次状态进行 diff，无需依赖内存、
 *   可跨进程重启（满足「与上次快照 diff」）。
 * - 内部状态行通过 [STATE_MARKER] 与 [STATE_WORK_ID] 标记，并在对外的 [observeChangeLogs] 中过滤掉，
 *   因此初始快照不会在时间线上产生「变化」记录。
 * - 真正的变更（新增收藏 / 状态 / 评分 / 短评 / 进度）由纯函数 [SnapshotDiff] 计算后落库为
 *   标准 [ChangeLogEntity]。
 *
 * 异常兜底：所有写操作在 [withContext] 的 IO 上下文执行；diff 计算为纯函数，不会抛出。
 */
@Singleton
class TimeMachineRepositoryImpl @Inject constructor(
    private val snapshotDao: SnapshotDao,
    private val dispatchers: DispatcherProvider,
) : TimeMachineRepository {

    private val json = Json { ignoreUnknownKeys = true }

    // --- captureInitialSnapshot  ---------------------------------

    override suspend fun captureInitialSnapshot(items: List<CollectionState>) {
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            val stateJson = encodeState(items)
            val snapshot = SnapshotEntity(
                id = UUID.randomUUID().toString(),
                takenAt = now,
                kind = SnapshotKind.INITIAL.name,
                payloadHash = hashOf(stateJson),
            )
            snapshotDao.insertSnapshot(snapshot)
            // 仅写内部状态行作为基线；初始快照不产生「变化」记录。
            snapshotDao.insertChangeLog(stateRow(snapshot.id, now, stateJson))
        }
    }

    // --- recordSyncDiff  ------------------------------

    override suspend fun recordSyncDiff(newItems: List<CollectionState>): List<ChangeLog> =
        withContext(dispatchers.io) {
            val now = System.currentTimeMillis()
            val previous = latestState()
            val changes: List<SnapshotChange> = SnapshotDiff.diff(previous, newItems)

            val stateJson = encodeState(newItems)
            val snapshot = SnapshotEntity(
                id = UUID.randomUUID().toString(),
                takenAt = now,
                kind = SnapshotKind.SYNC.name,
                payloadHash = hashOf(stateJson),
            )
            snapshotDao.insertSnapshot(snapshot)
            // 写新快照的内部状态行，供下次同步重建。
            snapshotDao.insertChangeLog(stateRow(snapshot.id, now, stateJson))

            // 落库真正的变更记录。
            val logs = changes.map { it.toEntity(snapshot.id, now) }
            if (logs.isNotEmpty()) {
                snapshotDao.upsertChangeLogs(logs)
            }
            logs.map { it.toDomain() }
        }

    // --- observers ---------------------------------------------------------

    override fun observeChangeLogs(): Flow<List<ChangeLog>> = snapshotDao.observeAllChangeLogs().map { rows ->
        rows.filterNot { it.isStateRow() }.map { it.toDomain() }
    }

    override fun observeSnapshots(): Flow<List<Snapshot>> =
        snapshotDao.observeSnapshots().map { rows -> rows.map { it.toDomainSnapshot() } }

    // --- internals ---------------------------------------------------------

    /** 重建上次快照的完整状态：读取最近快照的内部状态行并反序列化；无则视为空基线。 */
    private suspend fun latestState(): List<CollectionState> {
        val latest = snapshotDao.getLatestSnapshot() ?: return emptyList()
        val stateRow = snapshotDao.getChangeLogsForSnapshot(latest.id)
            .firstOrNull { it.isStateRow() }
            ?: return emptyList()
        return decodeState(stateRow.newValue)
    }

    private fun stateRow(snapshotId: String, changedAt: Long, stateJson: String): ChangeLogEntity = ChangeLogEntity(
        id = UUID.randomUUID().toString(),
        snapshotId = snapshotId,
        workId = STATE_WORK_ID,
        changeType = STATE_MARKER,
        field = null,
        oldValue = null,
        newValue = stateJson,
        changedAt = changedAt,
    )

    private fun encodeState(items: List<CollectionState>): String = json.encodeToString(items.map { it.toPersisted() })

    private fun decodeState(raw: String?): List<CollectionState> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            json.decodeFromString<List<PersistedState>>(raw).map { it.toDomain() }
        }.getOrDefault(emptyList())
    }

    /** 简单内容哈希，仅用于快速判定两次快照状态是否相同（payloadHash 语义）。 */
    private fun hashOf(content: String): String = content.hashCode().toString()

    private fun ChangeLogEntity.isStateRow(): Boolean = changeType == STATE_MARKER || workId == STATE_WORK_ID

    private fun SnapshotChange.toEntity(snapshotId: String, changedAt: Long): ChangeLogEntity = ChangeLogEntity(
        id = UUID.randomUUID().toString(),
        snapshotId = snapshotId,
        workId = workId,
        changeType = changeType.name,
        field = field,
        oldValue = oldValue,
        newValue = newValue,
        changedAt = changedAt,
    )

    private fun ChangeLogEntity.toDomain(): ChangeLog = ChangeLog(
        id = id,
        snapshotId = snapshotId,
        workId = workId,
        changeType = ChangeType.fromStorage(changeType),
        field = field,
        oldValue = oldValue,
        newValue = newValue,
        changedAt = changedAt,
    )

    private fun SnapshotEntity.toDomainSnapshot(): Snapshot = Snapshot(
        id = id,
        takenAt = takenAt,
        kind = SnapshotKind.fromStorage(kind),
        payloadHash = payloadHash,
    )

    private fun CollectionState.toPersisted(): PersistedState =
        PersistedState(workId, status, rating, shortReview, progress)

    /** 内部持久化 DTO：把 [CollectionState] 序列化进内部状态行的 `newValue`。 */
    @Serializable
    private data class PersistedState(
        val workId: String,
        val status: String? = null,
        val rating: Int? = null,
        val shortReview: String? = null,
        val progress: Int? = null,
    ) {
        fun toDomain(): CollectionState = CollectionState(workId, status, rating, shortReview, progress)
    }

    private companion object {
        /** 内部状态行的 `changeType` 标记，不对外暴露（在 [observeChangeLogs] 中过滤）。 */
        const val STATE_MARKER = "__state__"

        /** 内部状态行的 `workId` 哨兵值。 */
        const val STATE_WORK_ID = "__state__"
    }
}
