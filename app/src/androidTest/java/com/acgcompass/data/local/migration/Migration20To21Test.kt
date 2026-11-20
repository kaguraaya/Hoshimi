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
class Migration20To21Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AcgCompassDatabase::class.java,
    )

    @Test
    fun migrationAddsBackfillAndTaskRunTables() {
        helper.createDatabase(TEST_DB, 20).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            21,
            true,
            *AcgMigrations.ALL.filter { it.startVersion >= 20 }.toTypedArray(),
        ).use { db ->
            db.execSQL(
                "INSERT INTO rating_backfill_state " +
                    "(workId, lastAttemptAt, lastCheckedAt, failureCount, nextAttemptAt, lastOutcome) " +
                    "VALUES ('1', 1, NULL, 2, 5, 'TRANSIENT_FAILURE')",
            )
            db.execSQL(
                "INSERT INTO task_runs (taskKey, running, lastStartedAt, lastSuccessAt, lastFailureAt, " +
                    "lastError, lastSummary, updatedAt) VALUES ('sync', 0, 1, 1, NULL, NULL, 'ok', 1)",
            )
            db.query("SELECT failureCount FROM rating_backfill_state WHERE workId = '1'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val TEST_DB = "migration-20-21-test.db"
    }
}
