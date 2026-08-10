package com.acgcompass.data.local.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.acgcompass.data.local.AcgCompassDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration17To18Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AcgCompassDatabase::class.java,
    )

    @Test
    fun migrationPreservesTaskStateAndAddsTagLedger() {
        helper.createDatabase(TEST_DB, 17).use { db ->
            db.execSQL(
                "INSERT INTO background_task_state " +
                    "(taskKey, inputFingerprint, phase, completed, total, batchIndex, batchCount, " +
                    "automaticCount, reviewCount, rejectedCount, failedCount, remaining, startedAt, " +
                    "completedAt, updatedAt, error, retryAt, lastResult, notificationAcknowledged, " +
                    "candidateFingerprint) VALUES " +
                    "('ai_tag_classification', 'plan-1', 'READY', 0, 1, 0, 1, 0, 0, 0, 0, 1, " +
                    "1, NULL, 1, NULL, NULL, NULL, 0, 'candidate-1')",
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            18,
            true,
            *AcgMigrations.ALL.filter { it.startVersion >= 17 }.toTypedArray(),
        ).use { db ->
            db.query(
                "SELECT inputFingerprint, candidateFingerprint, personalInputFingerprint " +
                    "FROM background_task_state WHERE taskKey = 'ai_tag_classification'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("plan-1", cursor.getString(0))
                assertEquals("candidate-1", cursor.getString(1))
                assertEquals("", cursor.getString(2))
            }
            assertEquals(0, db.query("SELECT COUNT(*) FROM taste_tag_processing").singleInt())
        }
    }

    private fun android.database.Cursor.singleInt(): Int = use {
        moveToFirst()
        getInt(0)
    }

    private companion object {
        const val TEST_DB = "migration-17-18-test.db"
    }
}
