package com.acgcompass.data.local.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.acgcompass.data.local.AcgCompassDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration19To20Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AcgCompassDatabase::class.java,
    )

    @Test
    fun migrationAddsEmptyTrashTable() {
        helper.createDatabase(TEST_DB, 19).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            20,
            true,
            *AcgMigrations.ALL.filter { it.startVersion >= 19 }.toTypedArray(),
        ).use { db ->
            db.query("SELECT COUNT(*) FROM trash_items").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
            db.execSQL(
                "INSERT INTO trash_items " +
                    "(id, kind, workId, batchId, title, coverUrl, payloadJson, reason, removedAt) " +
                    "VALUES ('BACKLOG:1', 'BACKLOG', '1', 'b', 't', NULL, '{}', 'LOCAL', 1)",
            )
            db.query("SELECT purged FROM trash_items WHERE id = 'BACKLOG:1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-19-20-test.db"
    }
}
