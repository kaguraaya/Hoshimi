package com.acgcompass.data.sync

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BangumiSyncResilienceTest {
    @Test
    fun retriesTransientFailureUntilSuccess() = runTest {
        var calls = 0
        val result = retryTransient(listOf(10L, 10L)) {
            calls++
            if (calls < 3) AppResult.Failure(AppError.Network()) else AppResult.Success("ok")
        }
        assertEquals(3, calls)
        assertEquals(AppResult.Success("ok"), result)
    }

    @Test
    fun stopsAfterRetriesAreExhausted() = runTest {
        var calls = 0
        val result = retryTransient<String>(listOf(10L, 10L)) {
            calls++
            AppResult.Failure(AppError.Server())
        }
        assertEquals(3, calls)
        assertTrue(result is AppResult.Failure)
    }

    @Test
    fun doesNotRetryAuthFailure() = runTest {
        var calls = 0
        retryTransient<String>(listOf(10L, 10L)) {
            calls++
            AppResult.Failure(AppError.Unauthorized())
        }
        assertEquals(1, calls)
    }

    @Test
    fun classifiesTransientErrors() {
        assertTrue(AppError.Network().isTransient())
        assertTrue(AppError.Server().isTransient())
        assertTrue(AppError.RateLimited().isTransient())
        assertFalse(AppError.Unauthorized().isTransient())
        assertFalse(AppError.NotFound().isTransient())
    }

    @Test
    fun networkFailureMentionsApiRoute() {
        val hinted = AppError.Network(cause = "请求超时").withBangumiApiHint()
        assertTrue(hinted.cause.contains("请求超时"))
        assertTrue(hinted.cause.contains("API 地址"))
        val auth = AppError.Unauthorized()
        assertEquals(auth, auth.withBangumiApiHint())
    }
}
