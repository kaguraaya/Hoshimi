package com.acgcompass.data.backup

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.repository.TestDispatchers
import com.acgcompass.domain.repository.BackupRepository
import com.acgcompass.domain.repository.CsvKind
import com.acgcompass.domain.repository.ImportReport
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AutoBackupManagerTest {
    private lateinit var context: Context
    private lateinit var settings: SettingsDataStore
    private val repository = FakeBackupRepository()
    private lateinit var manager: AutoBackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, AutoBackupManager.DIR).deleteRecursively()
        settings = SettingsDataStore(context)
        manager = AutoBackupManager(context, repository, settings, TestDispatchers())
    }

    @Test
    fun `backup is written, verified and recorded`() = runTest {
        val result = manager.backupNow(now = 5_000L)

        assertTrue(result is AppResult.Success)
        val files = manager.list()
        assertEquals(1, files.size)
        assertTrue(files.first().file.readText().contains("schemaVersion"))
        assertEquals(5_000L, settings.autoBackupStatus.first().first)
        assertTrue(
            File(context.filesDir, AutoBackupManager.DIR).listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun `unreadable export is rejected and leaves no file`() = runTest {
        repository.payload = "{not json"

        val result = manager.backupNow(now = 6_000L)

        assertTrue(result is AppResult.Failure)
        assertTrue(manager.list().isEmpty())
        assertNotNull(settings.autoBackupStatus.first().second)
        assertTrue(File(context.filesDir, AutoBackupManager.DIR).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `only three newest backups are kept`() = runTest {
        repeat(5) { index ->
            manager.backupNow(now = 1_000_000L + index * 1_000L)
            manager.list().first().file.setLastModified(1_000_000L + index * 1_000L)
        }
        assertEquals(3, manager.list().size)
    }

    @Test
    fun `runIfDue skips a recent backup and runs an old one`() = runTest {
        settings.setAutoBackupEnabled(true)
        manager.backupNow(now = 10_000L)
        manager.runIfDue(now = 10_000L + 60_000L)
        assertEquals(10_000L, settings.autoBackupStatus.first().first)

        val weekLater = 10_000L + AutoBackupManager.INTERVAL_DAYS * 24 * 60 * 60 * 1000
        manager.runIfDue(now = weekLater)
        assertEquals(weekLater, settings.autoBackupStatus.first().first)
    }

    @Test
    fun `restore imports the backup content`() = runTest {
        manager.backupNow(now = 20_000L)

        val result = manager.restore(manager.list().first())

        assertTrue(result is AppResult.Success)
        assertEquals(repository.payload, repository.imported)
    }

    private class FakeBackupRepository : BackupRepository {
        var payload: String = BackupSerializer.serialize(BackupEnvelope(exportedAt = 1L))
        var imported: String? = null

        override suspend fun exportBackup(includeCredentials: Boolean): AppResult<String> = AppResult.Success(payload)

        override suspend fun importBackup(json: String): AppResult<ImportReport> {
            imported = json
            return if (json.isBlank()) {
                AppResult.Failure(AppError.FieldMissing(cause = "空"))
            } else {
                AppResult.Success(ImportReport(added = 0, updated = 0, conflicts = emptyList()))
            }
        }

        override suspend fun exportCsv(kind: CsvKind): AppResult<String> = AppResult.Success("")
    }
}
