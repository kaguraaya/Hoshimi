package com.acgcompass.core.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield

class RunCatchingCancellableTest : StringSpec({
    "keeps the original failure" {
        val result = runCatchingCancellable { error("boom") }
        result.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
    }

    "returns success values" {
        runCatchingCancellable { 42 }.getOrNull() shouldBe 42
    }

    "rethrows cancellation instead of swallowing it" {
        shouldThrow<CancellationException> {
            runCatchingCancellable { throw CancellationException("cancelled") }
        }
    }

    "a cancelled coroutine stops instead of running the code after the call" {
        var reachedAfter = false
        val gate = CompletableDeferred<Unit>()
        coroutineScope {
            val job = async {
                runCatchingCancellable { gate.await() }
                reachedAfter = true
            }
            yield()
            job.cancel()
        }
        reachedAfter shouldBe false
    }
})
