package com.acgcompass.data.local.migration

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.acgcompass.data.local.AcgCompassDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration12To13Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AcgCompassDatabase::class.java,
    )

    @Test
    fun migrationPreservesPersonalAndCacheRowsWhileAddingContinuationState() {
        helper.createDatabase(TEST_DB, 12).use { db ->
            db.execSQL(
                "INSERT INTO user_collections " +
                    "(id, source, sourceItemId, localWorkId, status, rating, progress, comment, tags, " +
                    "updatedAt, syncedAt, sourceUpdatedAt, isPrivate) VALUES " +
                    "('personal-1', 'VNDB', 'v1', 'v1', 'WISH', 8, NULL, 'note', '[]', 1, 1, NULL, 1)",
            )
            db.execSQL(
                "INSERT INTO cache_fill_state " +
                    "(source, nextPage, exhausted, lastRunAt, lastResult, failureReason, updatedAt) VALUES " +
                    "('JIKAN', 7, 0, 100, 'FAILED', 'limited', 100)",
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            13,
            true,
            *AcgMigrations.ALL.filter { it.startVersion >= 12 }.toTypedArray(),
        ).use { db ->
            assertEquals(1, db.query("SELECT COUNT(*) FROM user_collections").singleInt())
            db.query(
                "SELECT nextPage, consecutiveFailures, cooldownUntil, lastSuccessAt " +
                    "FROM cache_fill_state WHERE source = 'JIKAN'",
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(7, cursor.getInt(0))
                assertEquals(0, cursor.getInt(1))
                assertNull(cursor.getString(2))
                assertNull(cursor.getString(3))
            }
        }
    }

    @Test
    fun sourceCoverageCountsUniquePublicWorksAndExcludesProtectedRows() {
        helper.createDatabase(COVERAGE_TEST_DB, 12).use { db ->
            db.execSQL(
                "INSERT INTO public_cache_entries " +
                    "(workId, originSource, qualityScore, estimatedBytes, cachedAt, " +
                    "lastAccessedAt, protected) VALUES " +
                    "('w1', 'BANGUMI', 1, 100, 1, 1, 0), " +
                    "('w2', 'VNDB', 1, 100, 1, 1, 0), " +
                    "('w3', 'BANGUMI', 1, 100, 1, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO source_links " +
                    "(id, workId, sourceId, sourceItemId, matchConfidence, " +
                    "userOverridden, linkedAt) VALUES " +
                    "('l1', 'w1', 'JIKAN', 'j1', 1, 0, 1), " +
                    "('l2', 'w1', 'JIKAN', 'j1-duplicate', 1, 0, 1), " +
                    "('l3', 'w2', 'BANGUMI', 'b2', 1, 0, 1), " +
                    "('l4', 'w3', 'ANILIST', 'a3', 1, 0, 1)",
            )
        }

        helper.runMigrationsAndValidate(
            COVERAGE_TEST_DB,
            13,
            true,
            *AcgMigrations.ALL.filter { it.startVersion >= 12 }.toTypedArray(),
        ).close()

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(context, AcgCompassDatabase::class.java, COVERAGE_TEST_DB)
            .addMigrations(*AcgMigrations.ALL)
            .build()
        try {
            runBlocking {
                val dao = database.cacheDao()
                val usage = dao.observePublicUsage().first()
                val coverage = dao.observeSourceCoverage().first().associate { it.sourceId to it.workCount }

                assertEquals(2, usage.workCount)
                assertEquals(1, usage.protectedCount)
                assertEquals(2, coverage["BANGUMI"])
                assertEquals(1, coverage["JIKAN"])
                assertEquals(1, coverage["VNDB"])
                assertEquals(null, coverage["ANILIST"])
                assertEquals(1, dao.countSourceCoverage("JIKAN"))
                assertEquals(0, dao.countSourceCoverage("ANILIST"))
            }
        } finally {
            database.close()
            context.deleteDatabase(COVERAGE_TEST_DB)
        }
    }

    private fun android.database.Cursor.singleInt(): Int = use {
        moveToFirst()
        getInt(0)
    }

    private companion object {
        const val TEST_DB = "migration-12-13-test.db"
        const val COVERAGE_TEST_DB = "migration-12-13-coverage-test.db"
    }
}
