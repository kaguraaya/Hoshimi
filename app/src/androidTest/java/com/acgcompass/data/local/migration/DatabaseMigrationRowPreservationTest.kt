package com.acgcompass.data.local.migration

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.entity.WorkEntity
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Property-based migration test for [AcgCompassDatabase] .
 *
 *
 * For any legal old-version DB content, after a Room version migration each business table's
 * row count and primary-key set equals the pre-migration state — no loss, no duplication.
 *
 * The test inserts N random rows into the representative `works` table at the start schema using
 * [MigrationTestHelper], runs every migration registered in [AcgMigrations.ALL], then re-reads the
 * rows and asserts the row count + primary-key set are preserved.
 *
 * At schema version 1 there is no registered v1→v2 migration yet ([AcgMigrations.ALL] is empty),
 * so the invariant is demonstrated generatively via an open → insert → reopen round-trip through
 * Room (which also validates the exported schema). The test is written parametrically over
 * [AcgMigrations.ALL], so the moment a real migration is added it is automatically exercised by
 * [MigrationTestHelper.runMigrationsAndValidate] without changing this test.
 *
 * Instrumented Room tests are device-heavy, so a modest iteration count ([ITERATIONS]) is used for
 * the generative loop (each iteration opens/closes a fresh on-device database).
 *
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationRowPreservationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AcgCompassDatabase::class.java,
    )

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     *  — row count and primary-key set of the `works` table survive the full chain of
     * registered Room migrations (or an identity reopen when none are registered yet).
     */
    @Test
    fun migrationsPreserveWorksRowCountAndPrimaryKeySet() = runBlocking {
        val migrations = AcgMigrations.ALL
        val latestVersion = migrations.maxOfOrNull { it.endVersion } ?: START_VERSION

        checkAll(PropTestConfig(iterations = ITERATIONS), worksArb) { works ->
            // Fresh database file each iteration so inserted rows are exactly `works`.
            context.deleteDatabase(TEST_DB)

            // 1. Create the start schema (v1) and insert the legal old-version content.
            helper.createDatabase(TEST_DB, START_VERSION).use { db ->
                works.forEach { insertWork(db, it) }
            }
            val expectedIds = works.map { it.id }.toSet()
            // Sanity: the generator must produce distinct primary keys.
            assertEquals("generator must produce distinct primary keys", works.size, expectedIds.size)

            // 2. Apply migrations (if any) and read back, otherwise reopen via Room (identity).
            val observedIds: Set<String> =
                if (migrations.isNotEmpty() && latestVersion > START_VERSION) {
                    helper.runMigrationsAndValidate(
                        TEST_DB,
                        latestVersion,
                        /* validateDroppedTables = */
                        true,
                        *migrations,
                    ).use { migrated -> readWorkIds(migrated) }
                } else {
                    val room = Room.databaseBuilder(
                        context,
                        AcgCompassDatabase::class.java,
                        TEST_DB,
                    ).addMigrations(*migrations).build()
                    try {
                        readWorkIds(room.openHelper.readableDatabase)
                    } finally {
                        room.close()
                    }
                }

            // 3. No loss, no duplication: count and primary-key set are identical.
            assertEquals("row count must be preserved", works.size, observedIds.size)
            assertEquals("primary-key set must be preserved", expectedIds, observedIds)
        }

        // Guard so an accidentally-destructive future config is caught: the registry must stay
        // ascending and self-consistent (start < end), reflecting the no-data-loss policy.
        migrations.forEach { m ->
            assertTrue(
                "migration ${m.startVersion}->${m.endVersion} must advance the version",
                m.endVersion > m.startVersion,
            )
        }
    }

    private fun insertWork(db: SupportSQLiteDatabase, work: WorkEntity) {
        db.execSQL(
            "INSERT INTO works (" +
                "id, canonicalTitle, titleJa, titleRomaji, titleEn, aliases, mediaType, year, " +
                "status, episodes, episodeMinutes, volumes, estPlayMinutes, coverUrl, " +
                "primarySource, completionCostBucket, createdAt, updatedAt" +
                ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(
                work.id,
                work.canonicalTitle,
                work.titleJa,
                work.titleRomaji,
                work.titleEn,
                // aliases is a non-null List<String> column persisted as a JSON string.
                "[]",
                work.mediaType,
                work.year,
                work.status,
                work.episodes,
                work.episodeMinutes,
                work.volumes,
                work.estPlayMinutes,
                work.coverUrl,
                work.primarySource,
                work.completionCostBucket,
                work.createdAt,
                work.updatedAt,
            ),
        )
    }

    private fun readWorkIds(db: SupportSQLiteDatabase): Set<String> {
        val ids = mutableSetOf<String>()
        db.query("SELECT id FROM works").use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            while (cursor.moveToNext()) {
                ids += cursor.getString(idIndex)
            }
        }
        return ids
    }

    private companion object {
        const val TEST_DB = "migration-row-preservation-test.db"
        const val START_VERSION = 1

        /**
         * Instrumented Room tests are slow; keep the device loop modest while still exercising
         * empty / small / mixed-nullability content. Pure invariant (row preservation) holds
         * regardless of N.
         */
        const val ITERATIONS = 25

        /** Generates one [WorkEntity] with a placeholder id (rewritten to be unique per list). */
        private val workEntityArb: Arb<WorkEntity> = arbitrary {
            WorkEntity(
                id = "placeholder",
                canonicalTitle = Arb.string(0, 24).bind(),
                titleJa = Arb.string(0, 16).orNull().bind(),
                titleRomaji = Arb.string(0, 16).orNull().bind(),
                titleEn = Arb.string(0, 16).orNull().bind(),
                aliases = emptyList(),
                mediaType = Arb.of("ANIME", "MANGA", "NOVEL", "GAME", "VN").bind(),
                year = Arb.int(1960, 2035).orNull().bind(),
                status = Arb.of("WATCHING", "DONE", "PLAN", "DROPPED", "ON_HOLD").bind(),
                episodes = Arb.int(0, 500).orNull().bind(),
                episodeMinutes = Arb.int(0, 180).orNull().bind(),
                volumes = Arb.int(0, 300).orNull().bind(),
                estPlayMinutes = Arb.int(0, 10_000).orNull().bind(),
                coverUrl = Arb.string(0, 40).orNull().bind(),
                primarySource = Arb.of("BANGUMI", "ANILIST", "JIKAN", "MAL", "VNDB").bind(),
                completionCostBucket = Arb.of("TONIGHT", "WEEKEND", "LONG_HAUL").orNull().bind(),
                createdAt = Arb.long(0L, Long.MAX_VALUE / 2).bind(),
                updatedAt = Arb.long(0L, Long.MAX_VALUE / 2).bind(),
            )
        }

        /** A list (possibly empty) of works with guaranteed-distinct primary keys. */
        private val worksArb: Arb<List<WorkEntity>> =
            Arb.list(workEntityArb, 0..12).map { list ->
                list.mapIndexed { index, work -> work.copy(id = "work-$index") }
            }
    }
}
