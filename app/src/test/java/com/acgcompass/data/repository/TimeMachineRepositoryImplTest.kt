package com.acgcompass.data.repository

import com.acgcompass.data.local.dao.SnapshotDao
import com.acgcompass.data.local.entity.ChangeLogEntity
import com.acgcompass.data.local.entity.SnapshotEntity
import com.acgcompass.domain.model.ChangeType
import com.acgcompass.domain.model.CollectionState
import com.acgcompass.domain.model.SnapshotKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest

/**
 * 本地时光机仓库单元测试。使用内存 Fake DAO，无 Room / Android 依赖。
 *
 * 覆盖：初始快照仅捕获不产生变化、后续同步 diff 生成变更、
 * 跨调用基于「上次快照」重建状态进行 diff、内部状态行不出现在对外的变更日志中。
 */
class TimeMachineRepositoryImplTest : StringSpec({

    fun item(id: String, status: String? = null, rating: Int? = null, review: String? = null, progress: Int? = null) =
        CollectionState(id, status, rating, review, progress)

    fun newRepo(): Pair<TimeMachineRepositoryImpl, FakeSnapshotDao> {
        val dao = FakeSnapshotDao()
        return TimeMachineRepositoryImpl(dao, TestDispatchers()) to dao
    }

    "captureInitialSnapshot stores an INITIAL snapshot and emits no public change logs " {
        runTest {
            val (repo, _) = newRepo()
            repo.captureInitialSnapshot(listOf(item("a", status = "想看"), item("b", status = "在看")))

            val snapshots = repo.observeSnapshots().first()
            snapshots shouldHaveSize 1
            snapshots.single().kind shouldBe SnapshotKind.INITIAL

            // 初始基线不产生「变化」记录。
            repo.observeChangeLogs().first() shouldBe emptyList()
        }
    }

    "recordSyncDiff diffs against latest snapshot and records changes " {
        runTest {
            val (repo, _) = newRepo()
            repo.captureInitialSnapshot(listOf(item("a", status = "想看")))

            val changes = repo.recordSyncDiff(
                listOf(item("a", status = "在看"), item("b", status = "想看")),
            )

            changes.map { it.changeType }.toSet() shouldBe setOf(ChangeType.STATUS, ChangeType.ADDED)
            val statusChange = changes.first { it.changeType == ChangeType.STATUS }
            statusChange.workId shouldBe "a"
            statusChange.oldValue shouldBe "想看"
            statusChange.newValue shouldBe "在看"

            // SYNC 快照已建立。
            repo.observeSnapshots().first().map { it.kind } shouldContainExactly
                listOf(SnapshotKind.SYNC, SnapshotKind.INITIAL)
            // 对外变更日志只含真正的变化（不含内部状态行）。
            repo.observeChangeLogs().first() shouldHaveSize 2
        }
    }

    "second sync diffs against the previous sync state, not the initial baseline" {
        runTest {
            val (repo, _) = newRepo()
            repo.captureInitialSnapshot(listOf(item("a", status = "想看")))
            repo.recordSyncDiff(listOf(item("a", status = "在看"), item("b", status = "想看")))

            // 第二次同步：a 从「在看」→「看过」并打分；b 不变。
            val changes = repo.recordSyncDiff(
                listOf(item("a", status = "看过", rating = 8), item("b", status = "想看")),
            )

            changes.map { it.changeType }.toSet() shouldBe setOf(ChangeType.STATUS, ChangeType.RATING)
            val status = changes.first { it.changeType == ChangeType.STATUS }
            status.oldValue shouldBe "在看" // 基于上次同步状态，而非初始「想看」。
            status.newValue shouldBe "看过"
            val rating = changes.first { it.changeType == ChangeType.RATING }
            rating.oldValue shouldBe null
            rating.newValue shouldBe "8"
        }
    }

    "recordSyncDiff with no real changes records a SYNC snapshot but empty change list" {
        runTest {
            val (repo, _) = newRepo()
            repo.captureInitialSnapshot(listOf(item("a", status = "想看")))

            val changes = repo.recordSyncDiff(listOf(item("a", status = "想看")))
            changes shouldBe emptyList()
            repo.observeSnapshots().first() shouldHaveSize 2
            repo.observeChangeLogs().first() shouldBe emptyList()
        }
    }
})

/** 内存 Fake [SnapshotDao]。`getLatestSnapshot` 以 takenAt 降序、插入顺序为次序，避免毫秒碰撞导致歧义。 */
private class FakeSnapshotDao : SnapshotDao {
    private data class Stamped(val entity: SnapshotEntity, val seq: Long)

    private val snapshots = LinkedHashMap<String, Stamped>()
    private var seq = 0L
    private val snapshotsFlow = MutableStateFlow<List<SnapshotEntity>>(emptyList())
    private val logs = LinkedHashMap<String, ChangeLogEntity>()
    private val logsFlow = MutableStateFlow<List<ChangeLogEntity>>(emptyList())

    private fun emitSnapshots() {
        snapshotsFlow.value = snapshots.values
            .sortedWith(compareByDescending<Stamped> { it.entity.takenAt }.thenByDescending { it.seq })
            .map { it.entity }
    }

    private fun emitLogs() {
        logsFlow.value = logs.values.sortedByDescending { it.changedAt }
    }

    override fun observeSnapshots(): Flow<List<SnapshotEntity>> = snapshotsFlow

    override suspend fun getAllSnapshots(): List<SnapshotEntity> = snapshots.values.map { it.entity }

    override suspend fun getSnapshot(id: String): SnapshotEntity? = snapshots[id]?.entity

    override suspend fun getLatestSnapshot(): SnapshotEntity? = snapshots.values
        .maxWithOrNull(compareBy<Stamped> { it.entity.takenAt }.thenBy { it.seq })
        ?.entity

    override suspend fun insertSnapshot(snapshot: SnapshotEntity) {
        snapshots[snapshot.id] = Stamped(snapshot, seq++)
        emitSnapshots()
    }

    override suspend fun upsertSnapshot(snapshot: SnapshotEntity) = insertSnapshot(snapshot)

    override suspend fun deleteSnapshot(snapshot: SnapshotEntity) {
        snapshots.remove(snapshot.id)
        emitSnapshots()
    }

    override fun observeAllChangeLogs(): Flow<List<ChangeLogEntity>> = logsFlow

    override suspend fun getAllChangeLogs(): List<ChangeLogEntity> = logs.values.toList()

    override fun observeChangeLogs(snapshotId: String): Flow<List<ChangeLogEntity>> =
        logsFlow.map { list -> list.filter { it.snapshotId == snapshotId } }

    override suspend fun getChangeLogsForSnapshot(snapshotId: String): List<ChangeLogEntity> =
        logs.values.filter { it.snapshotId == snapshotId }

    override fun observeChangeLogsForWork(workId: String): Flow<List<ChangeLogEntity>> =
        logsFlow.map { list -> list.filter { it.workId == workId } }

    override suspend fun insertChangeLog(log: ChangeLogEntity) {
        logs[log.id] = log
        emitLogs()
    }

    override suspend fun upsertChangeLogs(logs: List<ChangeLogEntity>) {
        logs.forEach { this.logs[it.id] = it }
        emitLogs()
    }

    override suspend fun deleteChangeLog(log: ChangeLogEntity) {
        logs.remove(log.id)
        emitLogs()
    }

    override suspend fun deleteChangeLogsForSnapshot(snapshotId: String) {
        logs.values.removeAll { it.snapshotId == snapshotId }
        emitLogs()
    }
}
