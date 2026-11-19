package com.acgcompass.data.local.migration

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.acgcompass.data.local.AcgCompassDatabase
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseUpgradeBackupTest {
    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(AcgCompassDatabase.DATABASE_NAME)
        dbFile.parentFile?.mkdirs()
        File(context.filesDir, "db-backups").deleteRecursively()
    }

    @After
    fun tearDown() {
        dbFile.delete()
        File(context.filesDir, "db-backups").deleteRecursively()
    }

    private fun createDatabase(version: Int, backlogRows: Int) {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            db.execSQL("CREATE TABLE backlog_items (id TEXT PRIMARY KEY)")
            repeat(backlogRows) { db.execSQL("INSERT INTO backlog_items VALUES ('w$it')") }
            db.version = version
        }
    }

    @Test
    fun `older database is copied with row counts and marked for verification`() {
        createDatabase(version = 19, backlogRows = 12)

        val dir = DatabaseUpgradeBackup.backupIfUpgrading(context, targetVersion = 20, now = 1_000L)

        assertNotNull(dir)
        assertTrue(File(dir, AcgCompassDatabase.DATABASE_NAME).isFile)
        val pending = DatabaseUpgradeBackup.pendingVerification(context)
        assertNotNull(pending)
        assertEquals(19, pending!!.meta.fromVersion)
        assertEquals(20, pending.meta.toVersion)
        assertEquals(12L, pending.meta.counts["backlog_items"])
        assertEquals(1, DatabaseUpgradeBackup.list(context).size)
    }

    @Test
    fun `current or missing database is not backed up`() {
        assertNull(DatabaseUpgradeBackup.backupIfUpgrading(context, targetVersion = 20))
        createDatabase(version = 20, backlogRows = 1)
        assertNull(DatabaseUpgradeBackup.backupIfUpgrading(context, targetVersion = 20))
        assertNull(DatabaseUpgradeBackup.pendingVerification(context))
    }

    @Test
    fun `only the two newest backups are kept`() {
        createDatabase(version = 18, backlogRows = 1)
        repeat(3) { index ->
            val dir = DatabaseUpgradeBackup.backupIfUpgrading(
                context,
                targetVersion = 20,
                now = 1_000L + index * 1_000L,
            )
            dir?.setLastModified(1_000L + index * 1_000L)
        }
        assertEquals(2, DatabaseUpgradeBackup.list(context).size)
    }

    @Test
    fun `restore puts the backed up file back`() {
        createDatabase(version = 19, backlogRows = 3)
        DatabaseUpgradeBackup.backupIfUpgrading(context, targetVersion = 20)
        dbFile.delete()

        DatabaseUpgradeBackup.restore(context, DatabaseUpgradeBackup.list(context).first())

        assertEquals(19, DatabaseUpgradeBackup.readUserVersion(dbFile))
        assertEquals(3L, DatabaseUpgradeBackup.countRows(dbFile)["backlog_items"])
    }
}
