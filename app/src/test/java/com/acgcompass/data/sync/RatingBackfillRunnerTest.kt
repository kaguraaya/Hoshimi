package com.acgcompass.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.domain.model.RatingBackfillOutcome
import com.acgcompass.domain.model.RatingBackfillReport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 一批补评分的执行：单部超时不拖住整批、被中止的那部下次不会又排第一、按平台汇总没查成的原因、整批限时。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RatingBackfillRunnerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AcgCompassDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.ratingBackfillDao()
    private val now = 10_000_000_000L

    @After
    fun close() = db.close()

    private fun runner(
        timeoutMs: Long = 1_000L,
        budgetMs: Long = 60_000L,
        backfill: suspend (String) -> RatingBackfillReport,
    ) = RatingBackfillRunner(dao, backfill, itemTimeoutMs = timeoutMs, budgetMs = budgetMs, nowMs = { now })

    @Test
    fun aStuckItemTimesOutAndTheRestOfTheBatchStillRuns() = runBlocking {
        val batch = runner(timeoutMs = 50L) { id ->
            if (id == "slow") awaitCancellation()
            RatingBackfillReport(RatingBackfillOutcome.CHECKED)
        }.run(listOf("slow", "a", "b"), RatingBackfillRunner.Progress())

        assertEquals(2, batch.checked)
        assertEquals(1, batch.failed)
        assertEquals(1, batch.timedOut)
        val slow = requireNotNull(dao.get("slow"))
        assertEquals(1, slow.failureCount)
        assertEquals(RatingBackfillOutcome.TRANSIENT_FAILURE.name, slow.lastOutcome)
    }

    @Test
    fun anItemInterruptedByCancellationIsLeasedSoTheNextRunSkipsIt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val progress = RatingBackfillRunner.Progress()
        val job = launch {
            runner(timeoutMs = 60_000L) {
                started.complete(Unit)
                awaitCancellation()
            }.run(listOf("a", "b"), progress)
        }
        started.await()
        job.cancelAndJoin()

        val leased = requireNotNull(dao.get("a"))
        assertEquals(0, leased.failureCount)
        assertEquals(now + RatingBackfillPolicy.LEASE_MS, leased.nextAttemptAt)
        assertEquals("a", progress.currentId)
        assertEquals(0, progress.done)
        assertTrue(dao.get("b") == null)
    }

    @Test
    fun aCancellationRaisedInsideARequestOnlyFailsThatItem() = runBlocking {
        val batch = runner { id ->
            if (id == "odd") throw java.util.concurrent.CancellationException("shared request cancelled")
            RatingBackfillReport(RatingBackfillOutcome.CHECKED)
        }.run(listOf("odd", "a", "b"), RatingBackfillRunner.Progress())

        assertEquals(2, batch.checked)
        assertEquals(1, batch.failed)
        assertEquals(RatingBackfillOutcome.TRANSIENT_FAILURE.name, requireNotNull(dao.get("odd")).lastOutcome)
        assertTrue(batch.summary().contains("请求被中途取消"))
    }

    @Test
    fun failureReasonsAreSummarisedMostCommonFirst() = runBlocking {
        val batch = runner { id ->
            val reason = if (id == "c") "条目：网络连不上或超时" else "AniList：请求太频繁被限速，稍后会自动再试"
            RatingBackfillReport(RatingBackfillOutcome.TRANSIENT_FAILURE, reason = reason)
        }.run(listOf("a", "b", "c"), RatingBackfillRunner.Progress())

        val summary = batch.summary()
        assertTrue(summary, summary.contains("原因：AniList：请求太频繁被限速，稍后会自动再试 2 部、条目：网络连不上或超时 1 部"))
    }

    @Test
    fun failedPlatformsAreCountedPerItem() = runBlocking {
        val batch = runner { id ->
            when (id) {
                "a", "b" -> RatingBackfillReport(RatingBackfillOutcome.TRANSIENT_FAILURE, setOf("ANILIST"))
                else -> RatingBackfillReport(RatingBackfillOutcome.TRANSIENT_FAILURE, setOf("ANILIST", "TENRAI"))
            }
        }.run(listOf("a", "b", "c"), RatingBackfillRunner.Progress())

        assertEquals(mapOf("ANILIST" to 3, "TENRAI" to 1), batch.failedSources)
        assertTrue(batch.summary().contains("AniList 3 部、Tenrai 1 部"))
    }

    @Test
    fun theTimeBudgetStopsTheBatchAndLeavesTheRestForLater() = runBlocking {
        var elapsed = 0L
        val batch = RatingBackfillRunner(
            dao = dao,
            backfill = {
                elapsed += 40_000L
                RatingBackfillReport(RatingBackfillOutcome.CHECKED)
            },
            itemTimeoutMs = 1_000L,
            budgetMs = 60_000L,
            nowMs = { now },
            elapsedMs = { elapsed },
        ).run(listOf("a", "b", "c", "d"), RatingBackfillRunner.Progress())

        assertEquals(2, batch.checked)
        assertEquals(2, batch.deferred)
    }
}
