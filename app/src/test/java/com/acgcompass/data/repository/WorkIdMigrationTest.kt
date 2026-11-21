package com.acgcompass.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.RatingEntity
import com.acgcompass.data.local.entity.SourceLinkEntity
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.entity.WorkFeatureEntity
import com.acgcompass.data.local.migration.WorkIdMigration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 旧的其它平台作品行改成「来源:编号」：附属数据跟着走，Bangumi 作品和有 Bangumi 收藏的行不动。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkIdMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AcgCompassDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val migration = WorkIdMigration(db.workIdMigrationDao())

    @After
    fun close() = db.close()

    @Test
    fun legacyForeignRowsMoveWithTheirData() = runBlocking {
        db.workDao().upsertAll(
            listOf(work("500", "ANILIST"), work("600", "BANGUMI"), work("700", "TENRAI"), work("800", "ANILIST")),
        )
        db.workDao().upsert(work("ANILIST:800", "ANILIST", title = "新数据"))
        db.sourceLinkDao().upsert(link("500:ANILIST:500", "500", "ANILIST", "500"))
        db.sourceLinkDao().upsert(link("BANGUMI:900", "500", "BANGUMI", "900"))
        db.ratingDao().upsert(rating("500", "ANILIST"))
        db.ratingDao().upsert(rating("800", "ANILIST"))
        db.ratingDao().upsert(rating("ANILIST:800", "ANILIST"))
        db.backlogDao().upsert(backlog("500"))
        db.userCollectionDao().upsert(collection("BANGUMI:700", "700", "BANGUMI"))

        val migrated = migration.run()

        assertEquals(2, migrated)
        assertNull(db.workDao().getById("500"))
        assertNotNull(db.workDao().getById("ANILIST:500"))
        assertEquals(
            setOf("ANILIST", "BANGUMI"),
            db.sourceLinkDao().getByWork("ANILIST:500").map { it.sourceId }.toSet(),
        )
        assertEquals("ANILIST:500:ANILIST", db.ratingDao().getByWork("ANILIST:500").single().id)
        assertEquals(listOf("ANILIST:500"), db.backlogDao().getAll().map { it.workId })
        // Bangumi 作品不动；有 Bangumi 收藏指向的旧行不动。
        assertNotNull(db.workDao().getById("600"))
        assertNotNull(db.workDao().getById("700"))
        // 新编号已存在：保留新数据，删掉旧行和并不进去的附属数据。
        assertNull(db.workDao().getById("800"))
        assertEquals("新数据", db.workDao().getById("ANILIST:800")?.canonicalTitle)
        assertEquals(emptyList<RatingEntity>(), db.ratingDao().getByWork("800"))
        // 再跑一次没有可改的。
        assertEquals(0, migration.run())
    }

    @Test
    fun staleFeatureRowsUnderOldOrNamespacedKeysAreRemoved() = runBlocking {
        // 旧 MAL 编号 4181 下存的是 CLANNAD 的资料，而 Bangumi 4181 是另一部作品。
        db.workDao().upsert(work("MAL:4181", "MAL", title = "CLANNAD ～AFTER STORY～"))
        db.workFeatureDao().upsertAll(
            listOf(
                feature("4181", "CLANNAD ～AFTER STORY～"),
                feature("MAL:4181", "CLANNAD ～AFTER STORY～"),
                feature("ANILIST:77", "别的作品"),
                // 标题对不上：这就是 Bangumi 那个条目自己的资料，保留。
                feature("4182", "战锤40K：战争黎明 II"),
            ),
        )
        db.workDao().upsert(work("MAL:4182", "MAL", title = "完全不同的作品"))

        migration.run()

        assertNull(db.workFeatureDao().getById("4181"))
        assertNull(db.workFeatureDao().getById("MAL:4181"))
        assertNull(db.workFeatureDao().getById("ANILIST:77"))
        assertNotNull(db.workFeatureDao().getById("4182"))
    }

    private fun feature(id: String, title: String) = WorkFeatureEntity(
        subjectId = id, tagCountsJson = "[]", staff = emptyList(), characters = emptyList(), cv = emptyList(),
        bangumiScore = 8f, bangumiVotes = 100, eps = 12, durationMin = 24, platform = "TV", mediaType = "ANIME",
        titles = listOf(title), updatedAt = 1L,
    )

    private fun work(id: String, source: String, title: String = "作品$id") = WorkEntity(
        id = id, canonicalTitle = title, titleJa = null, titleRomaji = null, titleEn = null, aliases = emptyList(),
        mediaType = "ANIME", year = 2020, status = "FINISHED", episodes = 12, episodeMinutes = 24, volumes = null,
        estPlayMinutes = null, coverUrl = null, primarySource = source, completionCostBucket = null,
        createdAt = 1L, updatedAt = 1L,
    )

    private fun link(id: String, workId: String, source: String, item: String) = SourceLinkEntity(
        id = id,
        workId = workId,
        sourceId = source,
        sourceItemId = item,
        matchConfidence = 1f,
        userOverridden = false,
        linkedAt = 1L,
    )

    private fun rating(workId: String, source: String) = RatingEntity(
        id = "$workId:$source",
        workId = workId,
        sourceId = source,
        score = 7f,
        voteCount = 100,
        rank = null,
        fetchedAt = 1L,
        missing = false,
    )

    private fun backlog(workId: String) = BacklogItemEntity(
        workId = workId,
        priority = "MEDIUM",
        moodTags = emptyList(),
        riskTags = emptyList(),
        note = null,
        addedAt = 1L,
        dustDays = 0,
        inDustMuseum = false,
    )

    private fun collection(id: String, workId: String, source: String) = UserCollectionEntity(
        id = id, source = source, sourceItemId = workId, localWorkId = workId, status = "看过", rating = 8,
        progress = null, comment = null, tags = emptyList(), updatedAt = 1L, syncedAt = 1L, sourceUpdatedAt = null,
    )
}
