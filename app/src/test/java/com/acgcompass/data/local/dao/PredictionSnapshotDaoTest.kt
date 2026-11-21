package com.acgcompass.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.entity.PredictionSnapshotEntity
import com.acgcompass.data.local.entity.UserCollectionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PredictionSnapshotDaoTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AcgCompassDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.predictionSnapshotDao()

    @After
    fun close() = db.close()

    @Test
    fun keepsTheFirstSnapshotPerWorkAndVersion() = runBlocking {
        dao.insertAll(listOf(snapshot("1", "gate2", createdAt = 10L, predicted = 7.0)))
        dao.insertAll(
            listOf(
                snapshot("1", "gate2", createdAt = 20L, predicted = 9.0),
                snapshot("1", "legacy1", createdAt = 20L, predicted = 6.5),
            ),
        )

        val rows = dao.getAll()
        assertEquals(2, rows.size)
        assertEquals(7.0, rows.single { it.modelVersion == "gate2" }.predictedRating)
        assertEquals(listOf("1"), dao.recordedAmong("gate2", listOf("1", "2")))
    }

    @Test
    fun reconciledJoinsLaterBangumiRatings() = runBlocking {
        dao.insertAll(listOf(snapshot("1", "gate2", 10L, 7.0), snapshot("2", "gate2", 10L, 6.0)))
        db.userCollectionDao().upsertAll(
            listOf(collection("1", rating = 8, source = "BANGUMI"), collection("2", rating = 5, source = "ANILIST")),
        )

        val rows = dao.reconciled()

        assertEquals(1, rows.size)
        assertEquals("1", rows.single().workId)
        assertEquals(8, rows.single().actualRating)
    }

    @Test
    fun trimKeepsTheNewestRows() = runBlocking {
        dao.insertAll((1..5).map { snapshot("$it", "gate2", createdAt = it.toLong(), predicted = 7.0) })

        dao.trimTo(2)

        assertEquals(listOf("4", "5"), dao.getAll().map { it.workId })
    }

    private fun snapshot(workId: String, version: String, createdAt: Long, predicted: Double) =
        PredictionSnapshotEntity(
            workId = workId,
            modelVersion = version,
            context = "DETAIL",
            createdAt = createdAt,
            predictedRating = predicted,
            lowRating = null,
            highRating = null,
            matchScore = 60,
            satisfaction = 55,
            profileSize = 50,
        )

    private fun collection(id: String, rating: Int, source: String) = UserCollectionEntity(
        id = "$source:$id",
        source = source,
        sourceItemId = id,
        localWorkId = id,
        status = "COLLECT",
        rating = rating,
        progress = null,
        comment = null,
        tags = emptyList(),
        updatedAt = 100L,
        syncedAt = 100L,
        sourceUpdatedAt = null,
    )
}
