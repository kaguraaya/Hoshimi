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
class Migration11To12Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AcgCompassDatabase::class.java,
    )

    @Test
    fun migrationAddsCacheTablesWithoutChangingPersonalRows() {
        helper.createDatabase(TEST_DB, 11).use { db ->
            db.execSQL(
                "INSERT INTO user_collections " +
                    "(id, source, sourceItemId, localWorkId, status, rating, progress, comment, tags, " +
                    "updatedAt, syncedAt, sourceUpdatedAt, isPrivate) VALUES " +
                    "('personal-1', 'VNDB', 'v1', 'v1', 'WISH', 8, NULL, 'note', '[]', 1, 1, NULL, 1)",
            )
        }
        helper.runMigrationsAndValidate(
            TEST_DB,
            12,
            true,
            *AcgMigrations.ALL.filter { it.startVersion >= 11 }.toTypedArray(),
        ).use { db ->
            assertEquals(
                1,
                db.query("SELECT COUNT(*) FROM user_collections").use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
            assertEquals(
                0,
                db.query("SELECT COUNT(*) FROM vndb_ranking_cache").use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
            assertEquals(
                0,
                db.query("SELECT COUNT(*) FROM cache_fill_state").use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
        }
    }

    private companion object {
        const val TEST_DB = "migration-11-12-test.db"
    }
}
