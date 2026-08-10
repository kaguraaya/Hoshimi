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
class Migration13To14Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AcgCompassDatabase::class.java,
    )

    @Test
    fun migrationPreservesPersonalRowsAndAddsResumableTaskTables() {
        helper.createDatabase(TEST_DB, 13).use { db ->
            db.execSQL(
                "INSERT INTO user_collections " +
                    "(id, source, sourceItemId, localWorkId, status, rating, progress, comment, tags, " +
                    "updatedAt, syncedAt, sourceUpdatedAt, isPrivate) VALUES " +
                    "('personal-1', 'BANGUMI', '1', '1', 'DOING', 9, 3, 'note', '[]', 1, 1, NULL, 0)",
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            14,
            true,
            *AcgMigrations.ALL.filter { it.startVersion >= 13 }.toTypedArray(),
        ).use { db ->
            assertEquals(1, db.query("SELECT COUNT(*) FROM user_collections").singleInt())
            assertEquals(0, db.query("SELECT COUNT(*) FROM background_task_state").singleInt())
            assertEquals(0, db.query("SELECT COUNT(*) FROM tag_classification_plan").singleInt())
        }
    }

    private fun android.database.Cursor.singleInt(): Int = use {
        moveToFirst()
        getInt(0)
    }

    private companion object {
        const val TEST_DB = "migration-13-14-test.db"
    }
}
