package com.acgcompass.data.sync

import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.repository.CollectionRemoval
import com.acgcompass.domain.model.TrashReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionMergePlannerTest {
    private val minute = 60_000L

    @Suppress("LongParameterList")
    private fun record(
        id: String,
        status: String,
        rating: Int? = null,
        updatedAt: Long,
        syncedAt: Long,
        sourceUpdatedAt: String? = "2026-11-01T00:00:00Z",
        source: String = "BANGUMI",
    ) = UserCollectionEntity(
        id = "$source:$id",
        source = source,
        sourceItemId = id,
        localWorkId = id,
        status = status,
        rating = rating,
        progress = null,
        comment = null,
        tags = emptyList(),
        updatedAt = updatedAt,
        syncedAt = syncedAt,
        sourceUpdatedAt = sourceUpdatedAt,
    )

    private fun remote(id: String, status: String, rating: Int? = null, at: Long?) =
        RemoteCollection(record(id, status, rating, updatedAt = 999 * minute, syncedAt = 999 * minute), at)

    @Test
    fun webChangesAfterAppChangesWin() {
        // 应用里加了 A（已推送），之后在网页上删了 A、给 B 评分：两边都以网页为准。
        val local = mapOf(
            "A" to record("A", "想看", updatedAt = 10 * minute, syncedAt = 10 * minute),
            "B" to record("B", "在看", updatedAt = 5 * minute, syncedAt = 5 * minute),
        )
        val plan = planCollectionMerge(
            remote = listOf(remote("B", "看过", rating = 8, at = 20 * minute)),
            local = local,
            removals = emptyMap(),
            pendingWish = emptySet(),
            complete = true,
        )
        assertEquals(listOf("A"), plan.remoteRemoved)
        assertEquals(listOf("B"), plan.upserts.map { it.localWorkId })
        assertEquals(8, plan.upserts.single().rating)
    }

    @Test
    fun newerLocalEditIsPushedInsteadOfOverwritten() {
        val local = mapOf("A" to record("A", "看过", rating = 9, updatedAt = 30 * minute, syncedAt = 10 * minute))
        val plan = planCollectionMerge(
            listOf(remote("A", "在看", at = 20 * minute)),
            local,
            emptyMap(),
            emptySet(),
            complete = true,
        )
        assertEquals(listOf("A"), plan.pushLocal)
        assertTrue(plan.upserts.isEmpty())
    }

    @Test
    fun olderLocalEditLosesToNewerWebChange() {
        val local = mapOf("A" to record("A", "看过", updatedAt = 15 * minute, syncedAt = 10 * minute))
        val plan = planCollectionMerge(listOf(remote("A", "抛弃", at = 20 * minute)), local, emptyMap(), emptySet(), true)
        assertEquals("抛弃", plan.upserts.single().status)
        assertTrue(plan.pushLocal.isEmpty())
    }

    @Test
    fun removedInAppStaysRemovedUntilWebChangesIt() {
        val removal = mapOf("A" to CollectionRemoval("COLLECTION:A", TrashReason.LOCAL, removedAt = 30 * minute))
        val earlier = listOf(remote("A", "想看", at = 20 * minute))
        val kept = planCollectionMerge(earlier, emptyMap(), removal, emptySet(), complete = true)
        assertEquals(listOf("A"), kept.keptRemoved)
        assertTrue(kept.upserts.isEmpty())

        val later = listOf(remote("A", "看过", at = 40 * minute))
        val revived = planCollectionMerge(later, emptyMap(), removal, emptySet(), complete = true)
        assertEquals(listOf("A"), revived.clearRemovals)
        assertEquals("看过", revived.upserts.single().status)
    }

    @Test
    fun incompleteFetchNeverRemoves() {
        val local = mapOf("A" to record("A", "想看", updatedAt = 10 * minute, syncedAt = 10 * minute))
        val plan = planCollectionMerge(emptyList(), local, emptyMap(), emptySet(), complete = false)
        assertTrue(plan.remoteRemoved.isEmpty())
    }

    @Test
    fun localOnlyAndPendingRecordsAreNotTreatedAsWebDeletions() {
        val local = mapOf(
            "neverRemote" to record(
                "neverRemote",
                "想看",
                updatedAt = 10 * minute,
                syncedAt = 10 * minute,
                sourceUpdatedAt = null,
            ),
            "queued" to record("queued", "想看", updatedAt = 10 * minute, syncedAt = 10 * minute),
            "edited" to record("edited", "看过", updatedAt = 20 * minute, syncedAt = 10 * minute),
            "vndb" to record("vndb", "看过", updatedAt = 10 * minute, syncedAt = 10 * minute, source = "VNDB"),
        )
        val plan = planCollectionMerge(emptyList(), local, emptyMap(), setOf("queued"), complete = true)
        assertTrue(plan.remoteRemoved.isEmpty())
        assertEquals(setOf("queued", "edited"), plan.pushLocal.toSet())
    }

    @Test
    fun parsesBangumiTimestamps() {
        assertEquals(1_763_424_000_000L, parseBangumiTime("2025-11-18T00:00:00Z"))
        assertEquals(1_763_424_000_000L, parseBangumiTime("2025-11-18T08:00:00+08:00"))
        assertNull(parseBangumiTime("garbage"))
        assertNull(parseBangumiTime(null))
    }
}
