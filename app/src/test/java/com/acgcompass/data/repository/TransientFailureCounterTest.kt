package com.acgcompass.data.repository

import com.acgcompass.core.common.AppError
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withContext

class TransientFailureCounterTest : StringSpec({
    "only network, rate limit and server errors count as transient" {
        val counter = TransientFailureCounter()
        withContext(counter) {
            recordTransientFailure(AppError.Network())
            recordTransientFailure(AppError.RateLimited())
            recordTransientFailure(AppError.Server())
            recordTransientFailure(AppError.NotFound())
            recordTransientFailure(AppError.FieldMissing())
            recordTransientFailure(null)
        }
        counter.count shouldBe 4
    }

    "without a counter in the context nothing happens" {
        recordTransientFailure(AppError.Network())
    }
})
