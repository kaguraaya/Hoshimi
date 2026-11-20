package com.acgcompass.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.RoomTransactionRunner
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.TrashItemEntity
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 同步写库中途出错（磁盘满、数据库锁、进程被杀前的异常）：已提交的批次完整，出错的那一批作品、收藏和
 * 移除标记一起回滚；再跑一次就全部写进去，结果和一次跑完一样。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CollectionMergeWriterFaultTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AcgCompassDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val transaction = RoomTransactionRunner(db)
    private val ids = (1..5).map { "$it" }
    private val upserts = ids.map(::collection)
    private val works = ids.associateWith(::work)

    @After
    fun close() = db.close()

    @Test
    fun failureInSecondBatchRollsBackOnlyThatBatchAndRerunCompletes() = runBlocking {
        // 4 号之前被移除过、远端又改过：移除标记要和 4 号的收藏一起处理。
        db.trashDao().upsert(marker("4"))
        val failing = failingCollections(failOnCall = 2)

        val thrown = runCatching { writer(failing).write(upserts, works, emptyMap(), listOf("4"), emptyList(), NOW) }
        assertTrue(thrown.exceptionOrNull() is IOException)

        // 1、2 所在的批次完整写入；3、4 所在的批次作品和收藏都没有；5 还没开始写。
        assertEquals(setOf("1", "2"), db.userCollectionDao().getAll().map { it.localWorkId }.toSet())
        assertNotNull(db.workDao().getById("2"))
        assertNull(db.workDao().getById("3"))
        assertNotNull(db.trashDao().getById("COLLECTION:4"))

        val written = writer(db.userCollectionDao()).write(upserts, works, emptyMap(), listOf("4"), emptyList(), NOW)

        assertEquals(5, written.added)
        assertEquals(ids.toSet(), db.userCollectionDao().getAll().map { it.localWorkId }.toSet())
        assertEquals(ids.toSet(), ids.filter { db.workDao().getById(it) != null }.toSet())
        assertNull(db.trashDao().getById("COLLECTION:4"))
    }

    @Test
    fun failureWhileClearingMarkerRollsBackTheCollectionsOfThatBatch() = runBlocking {
        db.trashDao().upsert(marker("1"))
        val failingClear: suspend (String) -> Unit = { throw IOException("database is locked") }
        val writer =
            CollectionMergeWriter(transaction, db.workDao(), db.userCollectionDao(), failingClear, chunkSize = 2)

        runCatching { writer.write(upserts, works, emptyMap(), listOf("1"), emptyList(), NOW) }

        assertTrue(db.userCollectionDao().getAll().isEmpty())
        assertNull(db.workDao().getById("1"))
        assertNotNull(db.trashDao().getById("COLLECTION:1"))
    }

    @Test
    fun obsoleteMarkersAreClearedAfterTheCollections() = runBlocking {
        db.trashDao().upsert(marker("9"))
        writer(db.userCollectionDao()).write(upserts, works, emptyMap(), emptyList(), listOf("9"), NOW)
        assertNull(db.trashDao().getById("COLLECTION:9"))
    }

    @Test
    fun newWishesAreReportedForTheBacklogOnlyWhenTheyFirstAppear() = runBlocking {
        val wish = listOf(collection("1", status = "想看"), collection("2", status = "想看"))
        val local = mapOf("2" to collection("2", status = "在看"))
        val written = writer(db.userCollectionDao()).write(wish, works, local, emptyList(), emptyList(), NOW)
        assertEquals(listOf("1"), written.backlogWorks.map { it.id })
        assertEquals(1, written.added)
    }

    private fun writer(dao: UserCollectionDao) = CollectionMergeWriter(
        transaction,
        db.workDao(),
        dao,
        { db.trashDao().delete("COLLECTION:$it") },
        chunkSize = 2,
    )

    private fun failingCollections(failOnCall: Int): UserCollectionDao {
        val real = db.userCollectionDao()
        var calls = 0
        return object : UserCollectionDao by real {
            override suspend fun upsertAll(items: List<UserCollectionEntity>) {
                calls += 1
                if (calls == failOnCall) throw IOException("disk full")
                real.upsertAll(items)
            }
        }
    }

    private fun collection(id: String, status: String = "看过") = UserCollectionEntity(
        id = "BANGUMI:$id", source = "BANGUMI", sourceItemId = id, localWorkId = id, status = status, rating = 8,
        progress = null, comment = null, tags = emptyList(), updatedAt = 1L, syncedAt = 1L, sourceUpdatedAt = null,
    )

    private fun work(id: String) = Work(
        id = id,
        titles = Titles(canonical = "作品 $id"),
        mediaType = MediaType.ANIME,
        primarySource = SourceId.BANGUMI,
    )

    private fun marker(id: String) = TrashItemEntity(
        id = "COLLECTION:$id", kind = "COLLECTION", workId = id, batchId = "b", title = "作品 $id",
        coverUrl = null, payloadJson = null, reason = "USER", removedAt = 1L, purged = true,
    )

    private companion object {
        const val NOW = 1_000L
    }
}
