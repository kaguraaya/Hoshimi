package com.acgcompass.data.sync

import com.acgcompass.data.local.LocalTransactionRunner
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.domain.model.Work

/**
 * 把同步合并的结果写进本地数据库。每 [chunkSize] 条收藏一个短事务：这一批的作品资料、收藏记录和
 * 对应的「已移除」标记要么一起写进去，要么都不写；一次同步几千部时也不会长时间锁住数据库、卡住页面读取。
 * 中途失败时已提交的批次保持完整，没提交的下次同步重写（合并结果是幂等的）。
 */
internal class CollectionMergeWriter(
    private val transaction: LocalTransactionRunner,
    private val workDao: WorkDao,
    private val userCollectionDao: UserCollectionDao,
    private val clearRemoval: suspend (String) -> Unit,
    private val chunkSize: Int = DEFAULT_CHUNK,
) {
    /** 写入结果：新增条数、写过的作品、需要并入待补池的作品。 */
    class Written {
        var added = 0
        val syncedWorks = mutableListOf<Work>()
        val backlogWorks = mutableListOf<Work>()
    }

    /**
     * @param works 每条收藏对应的作品（收藏列表里没有条目资料的为 null）。
     * @param local 写入前本地已有的收藏，用来判断新增和「首次变成想看 / 在看」。
     * @param clearRemovals 要删掉移除标记的作品，属于本次写入的跟着那一批一起删。
     * @param obsoleteMarkers 远端也没有了的过期移除标记，最后单独一个事务删。
     */
    @Suppress("LongParameterList") // 写入需要的几组数据逐个传入，便于测试
    suspend fun write(
        upserts: List<UserCollectionEntity>,
        works: Map<String, Work>,
        local: Map<String, UserCollectionEntity>,
        clearRemovals: Collection<String>,
        obsoleteMarkers: Collection<String>,
        now: Long,
    ): Written {
        val written = Written()
        val clearSet = clearRemovals.toSet()
        upserts.chunked(chunkSize).forEach { chunk ->
            val chunkWritten = Written()
            transaction.run { writeChunk(chunk, works, local, clearSet, now, chunkWritten) }
            // 只有事务提交后才计入结果。
            written.added += chunkWritten.added
            written.syncedWorks += chunkWritten.syncedWorks
            written.backlogWorks += chunkWritten.backlogWorks
        }
        val upsertedIds = upserts.mapTo(HashSet()) { it.localWorkId }
        val leftover = clearSet.filterNot { it in upsertedIds } + obsoleteMarkers
        if (leftover.isNotEmpty()) transaction.run { leftover.forEach { clearRemoval(it) } }
        return written
    }

    @Suppress("LongParameterList") // 事务内的一批写入，参数都来自 [write]
    private suspend fun writeChunk(
        chunk: List<UserCollectionEntity>,
        works: Map<String, Work>,
        local: Map<String, UserCollectionEntity>,
        clearSet: Set<String>,
        now: Long,
        out: Written,
    ) {
        for (entity in chunk) {
            val existing = local[entity.localWorkId]
            if (existing == null) out.added += 1
            val work = works[entity.localWorkId] ?: continue
            persistWork(work, now)
            out.syncedWorks += work
            if (entity.status.isWishOrWatching() && !existing?.status.isWishOrWatching()) out.backlogWorks += work
        }
        userCollectionDao.upsertAll(chunk)
        chunk.map { it.localWorkId }.filter { it in clearSet }.forEach { clearRemoval(it) }
    }

    /** 写入作品；收藏列表里的精简条目没有信息框（别名），保留之前从搜索 / 详情写入的别名，跨源匹配要用。 */
    private suspend fun persistWork(work: Work, now: Long) {
        val existingWork = workDao.getById(work.id)
        val entity: WorkEntity = work.toEntity(createdAt = existingWork?.createdAt ?: now, updatedAt = now)
        workDao.upsert(
            if (entity.aliases.isEmpty() && existingWork != null) {
                entity.copy(
                    aliases = existingWork.aliases,
                )
            } else {
                entity
            },
        )
    }

    companion object {
        const val DEFAULT_CHUNK = 200
    }
}
