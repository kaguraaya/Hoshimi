package com.acgcompass.core.network.interceptor

import com.acgcompass.core.network.NetworkSource
import com.acgcompass.core.network.RateLimitPolicy
import com.acgcompass.core.network.RateLimiter
import com.acgcompass.core.network.RequestPriority
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Shared per-source limiter, concurrency gate, and server-directed backoff state. */
class RateLimiterRegistry private constructor(
    private val states: Map<NetworkSource, SourceState>,
    private val wallClock: () -> Long,
) {
    fun stateFor(sourceId: NetworkSource): SourceState? = states[sourceId]

    fun limiterFor(sourceId: NetworkSource): RateLimiter? = states[sourceId]?.limiter

    fun recordResponse(sourceId: NetworkSource, response: Response) {
        val state = states[sourceId] ?: return
        if (response.code == HTTP_TOO_MANY_REQUESTS || response.code == HTTP_SERVICE_UNAVAILABLE) {
            val serverDelay = retryAfterMillis(response) ?: rateLimitResetDelayMillis(response)
            state.recordRateLimited(serverDelay)
        } else if (response.isSuccessful) {
            state.recordSuccess()
        }
    }

    private fun retryAfterMillis(response: Response): Long? {
        val value = response.header("Retry-After")?.trim()?.takeIf(String::isNotEmpty)
        val seconds = value?.toLongOrNull()
        return when {
            seconds != null -> seconds.coerceAtLeast(0L) * MILLIS_PER_SECOND
            value != null -> runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            }.getOrNull()?.let { epochMillis -> (epochMillis - wallClock()).coerceAtLeast(0L) }
            else -> null
        }
    }

    private fun rateLimitResetDelayMillis(response: Response): Long? {
        val exhausted = response.header("X-RateLimit-Remaining")?.toLongOrNull() == 0L
        val resetSeconds = response.header("X-RateLimit-Reset")?.toLongOrNull()
        return resetSeconds
            ?.takeIf { exhausted }
            ?.let { (it * MILLIS_PER_SECOND - wallClock()).coerceAtLeast(0L) }
    }

    class SourceState internal constructor(
        internal val limiter: RateLimiter,
        private val maxConcurrentRequests: Int,
        private val maxBackoffMillis: Long,
        private val elapsedClock: () -> Long,
    ) {
        private val lock = ReentrantLock(true)
        private val available = lock.newCondition()
        private var inFlight = 0
        private var inFlightBackground = 0
        private var foregroundWaiters = 0
        private var consecutiveRateLimits = 0

        fun acquire(priority: RequestPriority): Lease {
            if (priority == RequestPriority.FOREGROUND) {
                lock.withLock { foregroundWaiters++ }
            }
            // 后台任务只用窗口容量的一部分，余量留给前台（用户正在等的详情 / 搜索）。
            val share = if (priority == RequestPriority.BACKGROUND) BACKGROUND_CAPACITY_SHARE else 1.0
            try {
                while (true) {
                    acquireAdmissionSlot(priority)
                    if (limiter.tryAcquireNow(share)) {
                        unregisterForegroundWaiter(priority)
                        return Lease { release(priority) }
                    }
                    release(priority)
                    waitForNextPermit(share)
                }
            } catch (error: InterruptedException) {
                unregisterForegroundWaiter(priority)
                Thread.currentThread().interrupt()
                throw error
            }
        }

        private fun acquireAdmissionSlot(priority: RequestPriority) {
            lock.withLock {
                while (!canAdmit(priority)) {
                    available.await()
                }
                inFlight++
                if (priority == RequestPriority.BACKGROUND) inFlightBackground++
            }
        }

        /**
         * 并发槽准入。后台请求额外受两条约束：有前台请求在等时让路；并发槽多于 1 个时至少留 1 个给前台
         * ——否则几个慢的后台请求占满全部槽位，前台只能等它们超时。
         */
        private fun canAdmit(priority: RequestPriority): Boolean {
            val hasFreeSlot = inFlight < maxConcurrentRequests
            if (priority != RequestPriority.BACKGROUND) return hasFreeSlot
            val backgroundSlots = (maxConcurrentRequests - 1).coerceAtLeast(1)
            return hasFreeSlot && foregroundWaiters == 0 && inFlightBackground < backgroundSlots
        }

        private fun unregisterForegroundWaiter(priority: RequestPriority) {
            if (priority == RequestPriority.FOREGROUND) {
                lock.withLock { foregroundWaiters-- }
            }
        }

        private fun waitForNextPermit(share: Double) {
            val delay = limiter.delayUntilNextPermitMillis(share)
            if (delay > 0L) Thread.sleep(delay.coerceAtMost(RETRY_SLICE_MILLIS))
        }

        fun recordRateLimited(serverDelayMillis: Long?) {
            lock.withLock {
                consecutiveRateLimits++
                val index = (consecutiveRateLimits - 1).coerceAtMost(BACKOFF_SCHEDULE_MILLIS.lastIndex)
                val localDelay = BACKOFF_SCHEDULE_MILLIS[index].coerceAtMost(maxBackoffMillis)
                val delay = maxOf(localDelay, serverDelayMillis ?: 0L)
                limiter.pauseUntil(elapsedClock() + delay)
            }
        }

        fun recordSuccess() {
            lock.withLock { consecutiveRateLimits = 0 }
        }

        private fun release(priority: RequestPriority) {
            lock.withLock {
                inFlight--
                if (priority == RequestPriority.BACKGROUND) inFlightBackground--
                available.signalAll()
            }
        }

        private companion object {
            const val RETRY_SLICE_MILLIS = 250L

            /** 后台任务最多占用限流窗口容量的比例。 */
            const val BACKGROUND_CAPACITY_SHARE = 0.5
        }
    }

    class Lease internal constructor(private val releaseAction: () -> Unit) : AutoCloseable {
        private var released = false

        override fun close() {
            if (!released) {
                released = true
                releaseAction()
            }
        }
    }

    companion object {
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVICE_UNAVAILABLE = 503
        private const val MILLIS_PER_SECOND = 1_000L
        private val BACKOFF_SCHEDULE_MILLIS = longArrayOf(30_000L, 120_000L, 300_000L, 900_000L)

        fun default(
            elapsedClock: () -> Long = { System.nanoTime() / 1_000_000L },
            wallClock: () -> Long = System::currentTimeMillis,
        ): RateLimiterRegistry = fromPolicies(RateLimitPolicy.DEFAULTS, elapsedClock, wallClock)

        fun fromPolicies(
            policies: List<RateLimitPolicy>,
            elapsedClock: () -> Long = { System.nanoTime() / 1_000_000L },
            wallClock: () -> Long = System::currentTimeMillis,
        ): RateLimiterRegistry = RateLimiterRegistry(
            states = policies.associate { policy ->
                policy.source to SourceState(
                    limiter = RateLimiter(
                        policy.rules,
                        throttleThreshold = policy.throttleThreshold,
                        clock = elapsedClock,
                    ),
                    maxConcurrentRequests = policy.maxConcurrentRequests,
                    maxBackoffMillis = policy.maxBackoffMillis,
                    elapsedClock = elapsedClock,
                )
            },
            wallClock = wallClock,
        )
    }
}

/** Request tag used by workers and prefetchers; untagged UI calls are foreground by default. */
data class RequestPriorityTag(val priority: RequestPriority)

fun Request.Builder.requestPriority(priority: RequestPriority): Request.Builder =
    tag(RequestPriorityTag::class.java, RequestPriorityTag(priority))

fun Request.requestPriority(): RequestPriority = tag(RequestPriorityTag::class.java)?.priority
    ?: sourceAuth()?.requestPriority
    ?: RequestPriority.FOREGROUND

class RateLimitInterceptor(
    private val registry: RateLimiterRegistry,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val sourceId = request.sourceAuth()?.sourceId
        val state = sourceId?.let(registry::stateFor)
        return if (sourceId == null || state == null) {
            chain.proceed(request)
        } else {
            state.acquire(request.requestPriority()).use {
                chain.proceed(request).also { response -> registry.recordResponse(sourceId, response) }
            }
        }
    }
}
