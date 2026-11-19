package com.acgcompass.core.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshGateTest {
    @Test
    fun launchIsThrottledWithinTheInterval() = runTest {
        var now = 1_000L
        val gate = RefreshGate(minIntervalMillis = 30_000L, clock = { now })
        assertNotNull(gate.launch(this) { })
        now = 11_000L
        assertNull(gate.launch(this) { })
        now = 32_000L
        assertNotNull(gate.launch(this) { })
    }

    @Test
    fun indicatorStopsAtTheCapWhileWorkKeepsRunning() = runTest {
        val gate =
            RefreshGate(minIntervalMillis = 0L, maxIndicatorMillis = 3_000L, clock = { testScheduler.currentTime })
        val job = gate.launch(this) { delay(10_000L) }
        gate.awaitIndicator(job, startedAt = 0L)
        assertEquals(3_000L, testScheduler.currentTime)
        assert(job?.isActive == true)
        job?.join()
        assertEquals(10_000L, testScheduler.currentTime)
    }
}
