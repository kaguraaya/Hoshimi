package com.acgcompass.data.local.migration

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.acgcompass.data.local.AcgCompassDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Migration10To11Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AcgCompassDatabase::class.java,
    )

    @Test
    fun migrationPreservesRowsAndProtectsPersonalWorks() {
        helper.createDatabase(TEST_DB, 10).use { db ->
            insertWork(db, "public-work", "Public", "JIKAN")
            insertWork(db, "personal-work", "Personal", "BANGUMI")
            db.execSQL(
                "INSERT INTO user_collections " +
                    "(id, source, sourceItemId, localWorkId, status, rating, progress, comment, tags, " +
                    "updatedAt, syncedAt, sourceUpdatedAt, isPrivate) " +
                    "VALUES ('collection-1', 'BANGUMI', '42', 'personal-work', 'WATCHING', NULL, NULL, " +
                    "NULL, '[]', 100, 100, NULL, 0)",
            )
            db.execSQL(
                "INSERT INTO ai_results " +
                    "(id, workId, taskType, generator, payloadJson, confidence, dataSources, generatedAt) " +
                    "VALUES ('ai-1', 'personal-work', 'RADAR', 'test', '{}', 0.8, NULL, 100)",
            )
            db.execSQL(
                "INSERT INTO tag_dimensions (tag, dimension, source, confidence, updatedAt) " +
                    "VALUES ('科幻', 'GENRE', 'AI', 0.9, 100)",
            )
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            11,
            true,
            *AcgMigrations.ALL.filter { it.startVersion >= 10 }.toTypedArray(),
        ).use { db ->
            assertEquals(2, scalarInt(db, "SELECT COUNT(*) FROM works"))
            assertEquals(2, scalarInt(db, "SELECT COUNT(*) FROM public_cache_entries"))
            assertEquals(
                1,
                scalarInt(
                    db,
                    "SELECT protected FROM public_cache_entries WHERE workId = 'personal-work'",
                ),
            )
            assertEquals(
                0,
                scalarInt(
                    db,
                    "SELECT protected FROM public_cache_entries WHERE workId = 'public-work'",
                ),
            )
            db.query("SELECT inputFingerprint, promptVersion FROM ai_results WHERE id = 'ai-1'").use { cursor ->
                cursor.moveToFirst()
                assertNull(cursor.getString(0))
                assertEquals(1, cursor.getInt(1))
            }
            db.query("SELECT classifierVersion, valid FROM tag_dimensions WHERE tag = '科幻'").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
                assertEquals(1, cursor.getInt(1))
            }
        }
    }

    private fun insertWork(db: androidx.sqlite.db.SupportSQLiteDatabase, id: String, title: String, source: String) {
        db.execSQL(
            "INSERT INTO works " +
                "(id, canonicalTitle, titleJa, titleRomaji, titleEn, titleCn, aliases, mediaType, year, " +
                "status, episodes, episodeMinutes, volumes, estPlayMinutes, coverUrl, primarySource, " +
                "completionCostBucket, summary, airDate, createdAt, updatedAt) " +
                "VALUES (?, ?, NULL, NULL, NULL, NULL, '[]', 'ANIME', NULL, 'UNKNOWN', NULL, NULL, " +
                "NULL, NULL, NULL, ?, NULL, NULL, NULL, 100, 200)",
            arrayOf(id, title, source),
        )
    }

    private fun scalarInt(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Int =
        db.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val TEST_DB = "migration-10-11-test.db"
    }
}
