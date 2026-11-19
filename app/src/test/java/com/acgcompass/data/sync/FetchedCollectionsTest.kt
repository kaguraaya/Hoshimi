package com.acgcompass.data.sync

import com.acgcompass.data.remote.bangumi.BangumiUserSubjectCollectionDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FetchedCollectionsTest {
    private fun item(id: Int) = BangumiUserSubjectCollectionDto(subjectId = id)

    @Test
    fun duplicatesFromShiftedPagesAreRemovedAndDoNotCountAsComplete() {
        // 分页时有人加了一条收藏：第 1 页最后一条被挤到第 2 页开头，重复出现；总数 4，实际只拿到 3 部不同的。
        val fetched = FetchedCollections.of(listOf(item(1), item(2), item(3), item(3)), total = 4, failedPages = 0)
        assertEquals(listOf(1, 2, 3), fetched.items.map { it.subjectId })
        assertFalse(fetched.complete)
    }

    @Test
    fun completeOnlyWithoutFailedPagesAndEnoughDistinctItems() {
        assertTrue(FetchedCollections.of(listOf(item(1), item(2)), total = 2, failedPages = 0).complete)
        assertFalse(FetchedCollections.of(listOf(item(1), item(2)), total = 2, failedPages = 1).complete)
    }

    @Test
    fun incompleteNoteNamesWhatWasNotWritten() {
        assertNull(SyncReport().incompleteNote)
        assertEquals("，标签、待补池没写进去", SyncReport(incomplete = listOf("标签", "待补池")).incompleteNote)
        assert(SyncReport(total = 3, incomplete = listOf("标签")).summary("u").endsWith("（共 3），标签没写进去"))
    }
}
