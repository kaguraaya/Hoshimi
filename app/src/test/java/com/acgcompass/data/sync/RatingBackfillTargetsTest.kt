package com.acgcompass.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.RatingEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingBackfillOutcome
import com.acgcompass.domain.model.SourceId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 补评分候选在数据库里筛选：规则与原来的内存筛选一致，再加上按表里的下次重试时间跳过。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RatingBackfillTargetsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AcgCompassDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.ratingBackfillDao()
    private val now = 10_000_000_000L
    private val checkedSince = now - RatingBackfillPolicy.CHECKED_RETRY_MS

    @After
    fun close() = db.close()

    @Test
    fun oneOrNoRatingIsPickedPersonalFirstThenByVotes() = runBlocking {
        db.workDao().upsertAll(listOf(work("a"), work("b"), work("c"), work("d"), work("vn", MediaType.VN)))
        listOf(
            rating("a", SourceId.BANGUMI, 7.5f, 100),
            rating("b", SourceId.BANGUMI, 8f, 9_000),
            rating("c", SourceId.BANGUMI, 7f, 50),
            rating("c", SourceId.ANILIST, 7.2f, 50),
            rating("vn", SourceId.BANGUMI, 7f, 50),
        ).forEach { db.ratingDao().upsert(it) }
        db.backlogDao().upsert(backlog("a"))

        // c 已有两个平台的分；vn 不是动画；d 还没有任何评分记录也会被选中。
        assertEquals(listOf("a", "b", "d"), dao.selectTargets(now, checkedSince, 10))
    }

    @Test
    fun recentlyCheckedOrOtherPlatformJustFetchedIsSkipped() = runBlocking {
        db.workDao().upsertAll(listOf(work("a"), work("b"), work("c")))
        db.ratingDao().upsert(rating("a", SourceId.BANGUMI, 7.5f, 100))
        db.ratingDao().upsert(rating("b", SourceId.BANGUMI, 8f, 900))
        db.ratingDao().upsert(rating("b", SourceId.ANILIST, 0f, 0).copy(missing = true, fetchedAt = now - 1_000L))
        dao.upsert(RatingBackfillPolicy.next("a", null, RatingBackfillOutcome.CHECKED, now - 1_000L))
        // c 上次没查成，重试时间已到 → 再选中。
        dao.upsert(
            RatingBackfillPolicy.next("c", null, RatingBackfillOutcome.TRANSIENT_FAILURE, now - 2 * HOUR),
        )

        assertEquals(listOf("c"), dao.selectTargets(now, checkedSince, 10))
    }

    @Test
    fun limitIsAppliedInSql() = runBlocking {
        db.workDao().upsertAll((1..30).map { work("w$it") })
        assertEquals(5, dao.selectTargets(now, checkedSince, 5).size)
    }

    private fun work(id: String, type: MediaType = MediaType.ANIME) = WorkEntity(
        id = id,
        canonicalTitle = id,
        titleJa = null,
        titleRomaji = null,
        titleEn = null,
        aliases = emptyList(),
        mediaType = type.name,
        year = null,
        status = "UNKNOWN",
        episodes = null,
        episodeMinutes = null,
        volumes = null,
        estPlayMinutes = null,
        coverUrl = null,
        primarySource = SourceId.BANGUMI.name,
        completionCostBucket = null,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun rating(workId: String, source: SourceId, score: Float, votes: Int) =
        RatingEntity("$workId-$source", workId, source.name, score, votes, null, now - 1L, false)

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

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
