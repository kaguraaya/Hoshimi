package com.acgcompass.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DefaultDispatcherProvider
import com.acgcompass.data.credential.CredentialStatus
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.RedactedCredentials
import com.acgcompass.data.credential.SecretBundle
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.PredictionSnapshotEntity
import com.acgcompass.data.local.entity.TrashItemEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.entity.WorkFeatureEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 导出 JSON 备份再导入到一个空数据库：作品、作品资料、待补池和回收站的字段都要原样回来。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRoundTripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source = memoryDatabase()
    private val target = memoryDatabase()

    @After
    fun close() {
        source.close()
        target.close()
    }

    @Test
    fun exportThenImportRestoresAllBackedUpFields() = runBlocking {
        val work = WorkEntity(
            id = "100", canonicalTitle = "作品", titleJa = "さくひん", titleRomaji = null, titleEn = null,
            titleCn = "作品中文名", aliases = listOf("别名"), mediaType = "ANIME", year = 2024, status = "FINISHED",
            episodes = 12, episodeMinutes = 24, volumes = null, estPlayMinutes = null,
            coverUrl = "https://example.com/a.jpg",
            primarySource = "BANGUMI", completionCostBucket = null, summary = "简介", airDate = "2024-04-05",
            createdAt = 1L, updatedAt = 2L,
        )
        val feature = WorkFeatureEntity(
            subjectId = "100", tagCountsJson = "[]", staff = emptyList(), characters = emptyList(), cv = emptyList(),
            bangumiScore = 7.5f, bangumiVotes = 300, eps = 12, durationMin = 24, platform = "TV", mediaType = "ANIME",
            titles = listOf("作品"), updatedAt = 3L, coverUrl = "https://example.com/b.jpg", airDate = "2024-04-05",
            ratingHistogram = "1,2,3,4,5,6,7,8,9,10",
        )
        val backlog = BacklogItemEntity(
            workId = "100", priority = "HIGH", moodTags = emptyList(), riskTags = emptyList(), note = null,
            addedAt = 4L, dustDays = 30, inDustMuseum = true, prevStatus = "在看",
        )
        val trash = TrashItemEntity(
            id = "COLLECTION:200", kind = "COLLECTION", workId = "200", batchId = "b", title = "移除的作品",
            coverUrl = null, payloadJson = null, reason = "USER", removedAt = 5L, purged = true,
        )
        source.workDao().upsert(work)
        source.workFeatureDao().upsert(feature)
        source.backlogDao().upsert(backlog)
        source.trashDao().upsert(trash)
        val snapshot = PredictionSnapshotEntity(
            workId = "300", modelVersion = "gate2", context = "DETAIL", createdAt = 6L, predictedRating = 7.4,
            lowRating = 6.1, highRating = 8.6, matchScore = 71, satisfaction = 64, profileSize = 120,
        )
        source.predictionSnapshotDao().insertAll(listOf(snapshot))

        val json = (repository(source).exportBackup(includeCredentials = false) as AppResult.Success).data
        val imported = repository(target).importBackup(json)

        assert(imported is AppResult.Success)
        assertEquals(work, target.workDao().getById("100"))
        assertEquals(feature, target.workFeatureDao().getById("100"))
        assertEquals(listOf(backlog), target.backlogDao().getAll())
        assertEquals(trash, target.trashDao().getById("COLLECTION:200"))
        assertEquals(listOf(snapshot), target.predictionSnapshotDao().getAll().map { it.copy(id = 0L) })
    }

    private fun memoryDatabase(): AcgCompassDatabase =
        Room.inMemoryDatabaseBuilder(context, AcgCompassDatabase::class.java).allowMainThreadQueries().build()

    private fun repository(db: AcgCompassDatabase) = BackupRepositoryImpl(
        workDao = db.workDao(),
        sourceLinkDao = db.sourceLinkDao(),
        ratingDao = db.ratingDao(),
        backlogDao = db.backlogDao(),
        tagDao = db.tagDao(),
        importDao = db.importDao(),
        snapshotDao = db.snapshotDao(),
        tasteDao = db.tasteDao(),
        userCollectionDao = db.userCollectionDao(),
        workFeatureDao = db.workFeatureDao(),
        tagDimensionDao = db.tagDimensionDao(),
        trashDao = db.trashDao(),
        predictionSnapshotDao = db.predictionSnapshotDao(),
        database = db,
        settingsDataStore = SettingsDataStore(context),
        credentialStore = NoCredentials,
        dispatchers = DefaultDispatcherProvider(),
    )

    private object NoCredentials : CredentialStore {
        override suspend fun put(source: SourceId, secret: SecretBundle) = Unit
        override suspend fun get(source: SourceId): SecretBundle? = null
        override suspend fun clear(source: SourceId) = Unit
        override suspend fun clearAll() = Unit
        override fun observeStatus(): Flow<Map<SourceId, CredentialStatus>> = flowOf(emptyMap())
        override suspend fun exportRedacted(): RedactedCredentials = RedactedCredentials()
    }
}
