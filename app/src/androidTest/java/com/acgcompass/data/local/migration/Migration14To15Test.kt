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
class Migration14To15Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AcgCompassDatabase::class.java,
    )

    @Test
    fun migrationPreservesPersonalRowsFeaturesAndClassificationPlan() {
        helper.createDatabase(TEST_DB, 14).use { db ->
            db.execSQL(
                "INSERT INTO user_collections " +
                    "(id, source, sourceItemId, localWorkId, status, rating, progress, comment, tags, " +
                    "updatedAt, syncedAt, sourceUpdatedAt, isPrivate) VALUES " +
                    "('personal-1', 'BANGUMI', '1', '1', 'DONE', 9, 12, 'note', '[\"Sci-Fi\"]', 1, 1, NULL, 0)",
            )
            db.execSQL(
                "INSERT INTO ratings " +
                    "(id, workId, sourceId, score, voteCount, rank, fetchedAt, missing) VALUES " +
                    "('rating-1', '1', 'BANGUMI', 8.5, 100, NULL, 1, 0)",
            )
            db.execSQL(
                "INSERT INTO work_features " +
                    "(subjectId, tagCountsJson, staff, characters, cv, bangumiScore, bangumiVotes, " +
                    "eps, durationMin, platform, mediaType, titles, updatedAt) VALUES " +
                    "('1', '[{\"name\":\"Sci-Fi\",\"count\":10}]', '[]', '[]', '[]', 8.5, 100, " +
                    "12, 24, 'TV', 'ANIME', '[\"Example\"]', 123)",
            )
            db.execSQL(
                "INSERT INTO tag_classification_plan " +
                    "(tag, rawTag, planId, position, status, dimension, confidence, error, updatedAt) VALUES " +
                    "('Sci-Fi', 'Sci-Fi', 'plan-1', 0, 'PENDING', NULL, NULL, NULL, 123)",
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            15,
            true,
            *AcgMigrations.ALL.filter { it.startVersion >= 14 }.toTypedArray(),
        ).use { db ->
            assertEquals(1, db.query("SELECT COUNT(*) FROM user_collections").singleInt())
            assertEquals(1, db.query("SELECT COUNT(*) FROM ratings").singleInt())
            db.query(
                "SELECT baseDataUpdatedAt, extendedDataUpdatedAt, completeness " +
                    "FROM work_features WHERE subjectId = '1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(123L, cursor.getLong(0))
                assertEquals(123L, cursor.getLong(1))
                assertEquals("EXTENDED", cursor.getString(2))
            }
            db.query(
                "SELECT classifierVersion, candidateFingerprint FROM tag_classification_plan " +
                    "WHERE planId = 'plan-1' AND tag = 'Sci-Fi'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals("", cursor.getString(1))
            }
            assertEquals(0, db.query("SELECT COUNT(*) FROM broadcast_schedule").singleInt())
        }
    }

    private fun android.database.Cursor.singleInt(): Int = use {
        moveToFirst()
        getInt(0)
    }

    private companion object {
        const val TEST_DB = "migration-14-15-test.db"
    }
}
