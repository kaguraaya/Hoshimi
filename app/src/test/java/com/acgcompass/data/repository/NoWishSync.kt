package com.acgcompass.data.repository

import com.acgcompass.data.sync.WishSyncQueue

/** 测试用：不做任何 Bangumi 回写。 */
internal object NoWishSync : WishSyncQueue {
    override suspend fun enqueue(workIds: Collection<String>) = Unit
}

/** 测试用回收站：直接从待补池删除，不保存快照。 */
internal class DeletingLocalTrash(
    private val backlogDao: com.acgcompass.data.local.dao.BacklogDao,
) : LocalTrash {
    override suspend fun trashBacklog(
        workIds: List<String>,
        reason: com.acgcompass.domain.model.TrashReason,
        batchId: String,
        now: Long,
    ): Int {
        var moved = 0
        workIds.forEach { id ->
            if (backlogDao.getByWork(id) != null) {
                backlogDao.deleteByWork(id)
                moved += 1
            }
        }
        return moved
    }

    override suspend fun trashCollection(
        workId: String,
        reason: com.acgcompass.domain.model.TrashReason,
        batchId: String,
        now: Long,
    ): Boolean = false
}
