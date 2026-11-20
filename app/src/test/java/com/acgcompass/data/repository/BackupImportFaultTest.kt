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
import com.acgcompass.data.local.dao.TrashDao
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.TrashItemEntity
import com.acgcompass.data.local.entity.WorkEntity
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 导入备份写到一半出错（最后一张表写入失败）：整个导入回滚，本机数据保持导入前的样子，
 * 不会出现作品写进去了、回收站和设置没写进去的半截状态；修好后再导入一次就完整。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupImportFaultTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val source = memoryDatabase()
    private val target = memoryDatabase()

    @After
    fun close() {
        source.close()
        target.close()
    }

    @Test
    fun failedImportLeavesLocalDataUntouchedAndRetrySucceeds() = runBlocking {
        val work = WorkEntity(
            id = "100", canonicalTitle = "作品", titleJa = null, titleRomaji = null, titleEn = null,
            aliases = emptyList(), mediaType = "ANIME", year = 2024, status = "FINISHED",
            episodes = 12, episodeMinutes = 24, volumes = null, estPlayMinutes = null, coverUrl = null,
            primarySource = "BANGUMI", completionCostBucket = null, createdAt = 1L, updatedAt = 2L,
        )
        source.workDao().upsert(work)
        source.backlogDao().upsert(
            BacklogItemEntity(
                workId = "100",
                priority = "HIGH",
                moodTags = emptyList(),
                riskTags = emptyList(),
                note = null,
                addedAt = 4L,
                dustDays = 0,
                inDustMuseum = false,
            ),
        )
        source.trashDao().upsert(
            TrashItemEntity(
                id = "COLLECTION:200", kind = "COLLECTION", workId = "200", batchId = "b", title = "移除的作品",
                coverUrl = null, payloadJson = null, reason = "USER", removedAt = 5L, purged = true,
            ),
        )
        val json = (repository(source).exportBackup(includeCredentials = false) as AppResult.Success).data

        val failingTrash = object : TrashDao by target.trashDao() {
            override suspend fun upsertAll(items: List<TrashItemEntity>) = throw IOException("disk full")
        }
        val failed = repository(target, trashDao = failingTrash).importBackup(json)

        assertTrue(failed is AppResult.Failure)
        assertNull(target.workDao().getById("100"))
        assertTrue(target.backlogDao().getAll().isEmpty())

        val retried = repository(target).importBackup(json)
        assertTrue(retried is AppResult.Success)
        assertEquals(work, target.workDao().getById("100"))
        assertEquals(1, target.backlogDao().getAll().size)
        assertEquals("移除的作品", target.trashDao().getById("COLLECTION:200")?.title)
    }

    private fun memoryDatabase(): AcgCompassDatabase =
        Room.inMemoryDatabaseBuilder(context, AcgCompassDatabase::class.java).allowMainThreadQueries().build()

    private fun repository(db: AcgCompassDatabase, trashDao: TrashDao = db.trashDao()) = BackupRepositoryImpl(
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
        trashDao = trashDao,
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
