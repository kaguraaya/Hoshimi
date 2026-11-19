package com.acgcompass.core.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowThrottleTest {
    @Test
    fun firstValueImmediatelyThenOnlyTheLatestOfABurst() = runTest {
        val source = flow {
            emit(1)
            delay(10)
            emit(2)
            delay(10)
            emit(3)
            delay(10)
            emit(4)
            delay(1_000)
            emit(5)
        }
        val seen = source.throttleLatest(500) { testScheduler.currentTime }.toList()
        assertEquals(listOf(1, 4, 5), seen)
    }
}
