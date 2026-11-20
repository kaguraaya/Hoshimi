package com.acgcompass.data.tasks

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.domain.model.BackgroundTaskKind
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TaskRunRecorderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, AcgCompassDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val recorder = TaskRunRecorder(db.taskRunDao())

    @After
    fun close() = db.close()

    @Test
    fun startSuccessAndFailureAreRecorded() = runBlocking {
        recorder.started(BackgroundTaskKind.SYNC, now = 10L)
        assertTrue(db.taskRunDao().get("bangumi_sync")!!.running)

        recorder.finish(BackgroundTaskKind.SYNC, AppResult.Success(3)) { "共 $it 部" }
        val ok = db.taskRunDao().get("bangumi_sync")!!
        assertFalse(ok.running)
        assertNotNull(ok.lastSuccessAt)
        assertEquals("共 3 部", ok.lastSummary)

        recorder.finish(BackgroundTaskKind.SYNC, AppResult.Failure(AppError.RateLimited()))
        val failed = db.taskRunDao().get("bangumi_sync")!!
        assertEquals("请求太频繁被限速，稍后会自动再试", failed.lastError)
        assertEquals("共 3 部", failed.lastSummary)
    }

    @Test
    fun trackRethrowsAndRecordsTheFailure() = runBlocking {
        val thrown = runCatching {
            recorder.track<Unit>(BackgroundTaskKind.CACHE_FILL) { throw IOException("boom") }
        }.exceptionOrNull()
        assertTrue(thrown is IOException)
        val row = db.taskRunDao().get("cache_fill")!!
        assertFalse(row.running)
        assertEquals("网络连不上或超时", row.lastError)
    }

    @Test
    fun staleRunningFlagsAreClearedOnStartup() = runBlocking {
        recorder.started(BackgroundTaskKind.OUTBOX)
        recorder.clearStaleRunning()
        assertFalse(db.taskRunDao().get("bangumi_outbox")!!.running)
    }
}
