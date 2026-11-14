package com.acgcompass.data.repository

import com.acgcompass.data.sync.WishSyncQueue

/** 测试用：不做任何 Bangumi 回写。 */
internal object NoWishSync : WishSyncQueue {
    override suspend fun enqueue(workIds: Collection<String>) = Unit
}
