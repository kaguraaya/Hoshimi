package com.acgcompass.data.repository

import androidx.room.withTransaction
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.backup.BackupUserCollection
import com.acgcompass.data.backup.toBackup
import com.acgcompass.data.backup.toEntity
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.TrashDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.TrashItemEntity
import com.acgcompass.domain.model.TrashEntry
import com.acgcompass.domain.model.TrashKind
import com.acgcompass.domain.model.TrashReason
import com.acgcompass.domain.repository.TrashRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 把记录移进本地回收站（数据层内部使用；界面通过 [TrashRepository] 查看和恢复）。 */
interface LocalTrash {
    /** 把待补池条目移进回收站（同一批次），返回实际移入的条数。 */
    suspend fun trashBacklog(
        workIds: List<String>,
        reason: TrashReason,
        batchId: String,
        now: Long = System.currentTimeMillis(),
    ): Int

    /** 把一部作品的收藏移进回收站，没有收藏时返回 false。 */
    suspend fun trashCollection(
        workId: String,
        reason: TrashReason,
        batchId: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean
}

/**
 * 回收站实现。其它数据层组件通过 [trashBacklog] / [trashCollection] 把记录移进来；
 * 收藏的「已移除」标记（[collectionRemovals]）供 Bangumi 同步判断要不要把远端记录加回来。
 */
/** 一次移除的批次、原因和时间。 */
private data class TrashStamp(val batchId: String, val reason: TrashReason, val now: Long)

// 同时实现回收站界面接口和数据层内部的 LocalTrash，两组操作共用同一份事务与序列化逻辑。
@Suppress("TooManyFunctions")
@Singleton
class TrashRepositoryImpl @Inject constructor(
    private val database: AcgCompassDatabase,
    private val trashDao: TrashDao,
    private val backlogDao: BacklogDao,
    private val userCollectionDao: UserCollectionDao,
    private val workDao: WorkDao,
    private val dispatchers: DispatcherProvider,
) : TrashRepository, LocalTrash {

    override fun observeEntries(): Flow<List<TrashEntry>> =
        trashDao.observeVisible().map { rows -> rows.map { it.toDomain() } }

    override fun observeWork(workId: String): Flow<List<TrashEntry>> =
        trashDao.observeVisibleByWork(workId).map { rows -> rows.map { it.toDomain() } }

    /** 不在待补池里的 id 忽略。 */
    override suspend fun trashBacklog(workIds: List<String>, reason: TrashReason, batchId: String, now: Long): Int =
        withContext(dispatchers.io) {
            database.withTransaction {
                var moved = 0
                workIds.distinct().forEach { workId ->
                    val row = backlogDao.getByWork(workId) ?: return@forEach
                    trashDao.upsert(
                        snapshot(
                            kind = TrashKind.BACKLOG,
                            workId = workId,
                            payload = JSON.encodeToString(TrashBacklogPayload.serializer(), row.toPayload()),
                            stamp = TrashStamp(batchId, reason, now),
                        ),
                    )
                    backlogDao.deleteByWork(workId)
                    moved += 1
                }
                moved
            }
        }

    /** Bangumi 上的收藏不会因此改变：Bangumi 公开接口不能删除收藏，网页上的记录要用户自己移除。 */
    override suspend fun trashCollection(workId: String, reason: TrashReason, batchId: String, now: Long): Boolean =
        withContext(dispatchers.io) {
            database.withTransaction {
                val row = userCollectionDao.getByWork(workId) ?: return@withTransaction false
                trashDao.upsert(
                    snapshot(
                        kind = TrashKind.COLLECTION,
                        workId = workId,
                        payload = JSON.encodeToString(BackupUserCollection.serializer(), row.toBackup()),
                        stamp = TrashStamp(batchId, reason, now),
                    ),
                )
                userCollectionDao.deleteByWork(workId)
                true
            }
        }

    /** 收藏的移除记录（含已过保留期的标记），按作品 id 索引，供同步使用。 */
    suspend fun collectionRemovals(): Map<String, CollectionRemoval> = withContext(dispatchers.io) {
        trashDao.getByKind(TrashKind.COLLECTION.name).associate {
            it.workId to CollectionRemoval(it.id, TrashReason.fromStorage(it.reason), it.removedAt, it.purged)
        }
    }

    /** 删除某条收藏的移除记录（远端在移除之后又改过，以远端为准时调用）。 */
    suspend fun clearCollectionRemoval(workId: String) = withContext(dispatchers.io) {
        trashDao.delete(idOf(TrashKind.COLLECTION, workId))
    }

    override suspend fun restore(id: String): Boolean = withContext(dispatchers.io) {
        database.withTransaction {
            val row = trashDao.getById(id) ?: return@withTransaction false
            restoreRow(row).also { trashDao.delete(id) }
        }
    }

    override suspend fun restoreBatch(batchId: String): Int = withContext(dispatchers.io) {
        database.withTransaction {
            val rows = trashDao.getByBatch(batchId).filter { !it.purged }
            val restored = rows.count { restoreRow(it) }
            trashDao.deleteAll(rows.map { it.id })
            restored
        }
    }

    override suspend fun deleteForever(id: String) = withContext(dispatchers.io) {
        val row = trashDao.getById(id) ?: return@withContext
        if (row.keepsMarker()) trashDao.upsert(row.copy(payloadJson = null, purged = true)) else trashDao.delete(id)
    }

    override suspend fun emptyTrash() = withContext(dispatchers.io) {
        database.withTransaction {
            trashDao.getExpired(before = Long.MAX_VALUE).forEach { row ->
                if (row.keepsMarker()) {
                    trashDao.upsert(
                        row.copy(payloadJson = null, purged = true),
                    )
                } else {
                    trashDao.delete(row.id)
                }
            }
        }
    }

    override suspend fun purgeExpired(now: Long) = withContext(dispatchers.io) {
        val before = now - TrashRepository.RETENTION_DAYS * MILLIS_PER_DAY
        trashDao.getExpired(before).forEach { row ->
            if (row.keepsMarker()) {
                trashDao.upsert(
                    row.copy(payloadJson = null, purged = true),
                )
            } else {
                trashDao.delete(row.id)
            }
        }
    }

    /**
     * 写回原记录。已经有新记录（重新加入待补池 / 重新收藏）时保留新记录，返回 false。
     * Bangumi 已删除或切换账号移入的收藏恢复后标记为「未同步」，由待推送队列推回 Bangumi；
     * 在应用里移除的收藏从没改过 Bangumi，原样写回即可，两边的日期都不变。
     */
    private suspend fun restoreRow(row: TrashItemEntity): Boolean {
        val payload = row.payloadJson
        return when {
            payload == null -> false
            TrashKind.valueOf(row.kind) == TrashKind.BACKLOG -> restoreBacklog(row.workId, payload)
            else -> restoreCollection(row, payload)
        }
    }

    private suspend fun restoreBacklog(workId: String, payload: String): Boolean {
        if (backlogDao.getByWork(workId) != null) return false
        backlogDao.upsert(JSON.decodeFromString(TrashBacklogPayload.serializer(), payload).toEntity())
        return true
    }

    private suspend fun restoreCollection(row: TrashItemEntity, payload: String): Boolean {
        if (userCollectionDao.getByWork(row.workId) != null) return false
        val entity = JSON.decodeFromString(BackupUserCollection.serializer(), payload).toEntity()
        val pushBack = TrashReason.fromStorage(row.reason) != TrashReason.LOCAL
        userCollectionDao.upsert(if (pushBack) entity.copy(syncedAt = 0L) else entity)
        return true
    }

    private suspend fun snapshot(kind: TrashKind, workId: String, payload: String, stamp: TrashStamp): TrashItemEntity {
        val (batchId, reason, now) = stamp
        val work = workDao.getById(workId)
        return TrashItemEntity(
            id = idOf(kind, workId),
            kind = kind.name,
            workId = workId,
            batchId = batchId,
            title = work?.titleCn ?: work?.canonicalTitle ?: workId,
            coverUrl = work?.coverUrl,
            payloadJson = payload,
            reason = reason.name,
            removedAt = now,
        )
    }

    private fun TrashItemEntity.toDomain(): TrashEntry {
        val kind = TrashKind.valueOf(kind)
        val collection = if (kind == TrashKind.COLLECTION) payloadJson?.let(::decodeCollection) else null
        val backlog = if (kind == TrashKind.BACKLOG) payloadJson?.let(::decodeBacklog) else null
        return TrashEntry(
            id = id,
            kind = kind,
            workId = workId,
            batchId = batchId,
            title = title,
            coverUrl = coverUrl,
            reason = TrashReason.fromStorage(reason),
            removedAt = removedAt,
            status = collection?.status,
            rating = collection?.rating,
            addedAt = backlog?.addedAt ?: collection?.updatedAt,
        )
    }

    private fun decodeCollection(json: String): BackupUserCollection? =
        runCatchingCancellable { JSON.decodeFromString(BackupUserCollection.serializer(), json) }.getOrNull()

    private fun decodeBacklog(json: String): TrashBacklogPayload? =
        runCatchingCancellable { JSON.decodeFromString(TrashBacklogPayload.serializer(), json) }.getOrNull()

    /** 在应用里移除的收藏要长期保留「已移除」标记：Bangumi 上的收藏还在，否则同步会把它加回来。 */
    private fun TrashItemEntity.keepsMarker(): Boolean =
        kind == TrashKind.COLLECTION.name && TrashReason.fromStorage(reason) == TrashReason.LOCAL

    companion object {
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        private val JSON = Json { ignoreUnknownKeys = true }

        fun idOf(kind: TrashKind, workId: String): String = "${kind.name}:$workId"
    }
}

/** 收藏的移除记录：同步时用来判断远端记录是在移除之前还是之后改过的。 */
data class CollectionRemoval(
    val trashId: String,
    val reason: TrashReason,
    val removedAt: Long,
    val purged: Boolean = false,
)

/** 待补池条目的完整快照（含吃灰馆归档前的状态）。 */
@Serializable
internal data class TrashBacklogPayload(
    val workId: String,
    val priority: String,
    val moodTags: List<String> = emptyList(),
    val riskTags: List<String> = emptyList(),
    val note: String? = null,
    val addedAt: Long,
    val dustDays: Int = 0,
    val inDustMuseum: Boolean = false,
    val prevStatus: String? = null,
)

private fun BacklogItemEntity.toPayload() = TrashBacklogPayload(
    workId = workId,
    priority = priority,
    moodTags = moodTags,
    riskTags = riskTags,
    note = note,
    addedAt = addedAt,
    dustDays = dustDays,
    inDustMuseum = inDustMuseum,
    prevStatus = prevStatus,
)

private fun TrashBacklogPayload.toEntity() = BacklogItemEntity(
    workId = workId,
    priority = priority,
    moodTags = moodTags,
    riskTags = riskTags,
    note = note,
    addedAt = addedAt,
    dustDays = dustDays,
    inDustMuseum = inDustMuseum,
    prevStatus = prevStatus,
)
