package com.acgcompass.core.network

import com.acgcompass.core.network.interceptor.RateLimiterRegistry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

class RateLimitRegistryTest : StringSpec({
    "default request budgets match the conservative per-source policy" {
        val policies = RateLimitPolicy.DEFAULTS.associateBy { it.source }
        fun rules(source: NetworkSource) = policies.getValue(source).rules

        rules(NetworkSource.JIKAN) shouldBe listOf(
            RateLimiter.Rule(maxRequests = 1, windowMillis = 5_000L),
            RateLimiter.Rule.perMinute(10),
        )
        rules(NetworkSource.ANILIST) shouldBe listOf(RateLimiter.Rule.perSecond(1), RateLimiter.Rule.perMinute(28))
        rules(NetworkSource.VNDB) shouldBe listOf(RateLimiter.Rule.perSecond(1), RateLimiter.Rule.perMinute(30))
        rules(NetworkSource.MAL) shouldBe listOf(RateLimiter.Rule.perSecond(1), RateLimiter.Rule.perMinute(30))
        rules(NetworkSource.BANGUMI) shouldBe listOf(
            RateLimiter.Rule(maxRequests = 5, windowMillis = 4_000L),
            RateLimiter.Rule.perMinute(45),
        )
        // Bangumi 留两个并发槽：其中一个始终保留给前台，后台补缓存时详情/搜索不必排在它后面。
        policies.getValue(NetworkSource.BANGUMI).maxConcurrentRequests shouldBe 2
        policies.filterKeys { it != NetworkSource.BANGUMI }.values.all { it.maxConcurrentRequests == 1 } shouldBe true
    }

    "default policies cover every public metadata source conservatively" {
        val registry = RateLimiterRegistry.default(elapsedClock = { 0L }, wallClock = { 0L })

        NetworkSource.entries.filterNot { it == NetworkSource.AI_PROVIDER }.forEach { source ->
            (registry.limiterFor(source) != null) shouldBe true
        }

        val jikan = requireNotNull(registry.limiterFor(NetworkSource.JIKAN))
        jikan.reserve() shouldBe 0L
        jikan.reserve() shouldBeGreaterThanOrEqual 2_000L
    }

    "Retry-After is shared and stricter than local exponential backoff" {
        var elapsed = 10_000L
        val registry = RateLimiterRegistry.default(
            elapsedClock = { elapsed },
            wallClock = { 1_000_000L },
        )
        val response = Response.Builder()
            .request(Request.Builder().url("https://api.jikan.moe/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .header("Retry-After", "120")
            .build()

        registry.recordResponse(NetworkSource.JIKAN, response)

        requireNotNull(registry.limiterFor(NetworkSource.JIKAN)).reserve() shouldBeGreaterThanOrEqual 130_000L
        elapsed = 130_000L
    }

    "Jikan 503 responses use the conservative circuit-breaker schedule" {
        var elapsed = 0L
        val registry = RateLimiterRegistry.default(elapsedClock = { elapsed }, wallClock = { 0L })
        val response = Response.Builder()
            .request(Request.Builder().url("https://api.jikan.moe/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("Unavailable")
            .build()

        registry.recordResponse(NetworkSource.JIKAN, response)
        requireNotNull(registry.limiterFor(NetworkSource.JIKAN)).reserve() shouldBeGreaterThanOrEqual 30_000L
        elapsed = 30_000L
        registry.recordResponse(NetworkSource.JIKAN, response)
        requireNotNull(registry.limiterFor(NetworkSource.JIKAN)).reserve() shouldBeGreaterThanOrEqual 150_000L
    }

    "background cooldown reports the remaining backoff, including a global pause" {
        var now = 0L
        val limiter = RateLimiter(
            rules = listOf(RateLimiter.Rule(maxRequests = 4, windowMillis = 1_000L)),
            throttleThreshold = 1.0,
            clock = { now },
        )
        limiter.backgroundCooldownMillis() shouldBe 0L
        limiter.pauseBackgroundUntil(120_000L)
        limiter.backgroundCooldownMillis() shouldBe 120_000L
        now = 100_000L
        limiter.backgroundCooldownMillis() shouldBe 20_000L
        limiter.pauseUntil(400_000L)
        limiter.backgroundCooldownMillis() shouldBe 300_000L
        now = 500_000L
        limiter.backgroundCooldownMillis() shouldBe 0L
    }

    "background requests only use half of each window so foreground keeps headroom" {
        var now = 0L
        val limiter = RateLimiter(
            rules = listOf(RateLimiter.Rule(maxRequests = 4, windowMillis = 1_000L)),
            throttleThreshold = 1.0,
            clock = { now },
        )

        limiter.tryAcquireNow(capacityShare = 0.5) shouldBe true
        limiter.tryAcquireNow(capacityShare = 0.5) shouldBe true
        // 后台已用满它的一半份额：再要就被拒，并且要等到最早那次许可滑出窗口。
        limiter.tryAcquireNow(capacityShare = 0.5) shouldBe false
        limiter.delayUntilNextPermitMillis(capacityShare = 0.5) shouldBe 1_000L
        // 前台不受份额限制，剩下的两个名额立即可用。
        limiter.delayUntilNextPermitMillis() shouldBe 0L
        limiter.tryAcquireNow() shouldBe true
        limiter.tryAcquireNow() shouldBe true
        limiter.tryAcquireNow() shouldBe false

        now = 1_001L
        limiter.tryAcquireNow(capacityShare = 0.5) shouldBe true
    }

    "a single-permit window still lets background requests through" {
        var now = 0L
        val limiter = RateLimiter(
            rules = listOf(RateLimiter.Rule.perSecond(1)),
            throttleThreshold = 1.0,
            clock = { now },
        )

        limiter.tryAcquireNow(capacityShare = 0.5) shouldBe true
        limiter.tryAcquireNow(capacityShare = 0.5) shouldBe false
        now = 1_001L
        limiter.tryAcquireNow(capacityShare = 0.5) shouldBe true
    }

    "with two slots background holds at most one and foreground is admitted immediately" {
        val registry = RateLimiterRegistry.fromPolicies(
            policies = listOf(
                RateLimitPolicy.DEFAULTS.first { it.source == NetworkSource.BANGUMI }.copy(
                    rules = listOf(RateLimiter.Rule(maxRequests = 100, windowMillis = 1_000L)),
                    throttleThreshold = 1.0,
                ),
            ),
        )
        val state = requireNotNull(registry.stateFor(NetworkSource.BANGUMI))
        val pool = Executors.newFixedThreadPool(2)
        try {
            val firstBackground = state.acquire(RequestPriority.BACKGROUND)
            val secondBackground = pool.submit<RateLimiterRegistry.Lease> { state.acquire(RequestPriority.BACKGROUND) }
            // 第二个后台请求拿不到槽位（留给前台的那个不让它占）。
            runCatching { secondBackground.get(300, TimeUnit.MILLISECONDS) }.isFailure shouldBe true

            val foreground = pool.submit<RateLimiterRegistry.Lease> { state.acquire(RequestPriority.FOREGROUND) }
            foreground.get(2, TimeUnit.SECONDS).close()

            firstBackground.close()
            secondBackground.get(2, TimeUnit.SECONDS).close()
        } finally {
            pool.shutdownNow()
        }
    }

    "429 without a server delay backs off background only; foreground waits a few seconds" {
        var elapsed = 0L
        val registry = RateLimiterRegistry.default(elapsedClock = { elapsed }, wallClock = { 0L })
        val response = Response.Builder()
            .request(Request.Builder().url("https://api.bgm.tv/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .build()

        registry.recordResponse(NetworkSource.BANGUMI, response)
        val limiter = requireNotNull(registry.limiterFor(NetworkSource.BANGUMI))

        // 前台只停 5 秒，后台按退避表停 30 秒。
        limiter.delayUntilNextPermitMillis() shouldBe 5_000L
        limiter.delayUntilNextPermitMillis(capacityShare = 0.5) shouldBe 30_000L
        elapsed = 5_000L
        limiter.tryAcquireNow() shouldBe true
        limiter.tryAcquireNow(capacityShare = 0.5) shouldBe false
        elapsed = 30_000L
        limiter.tryAcquireNow(capacityShare = 0.5) shouldBe true
    }

    "every HTTP client lets more than five requests per host wait for the limiter" {
        val client = HttpClientFactory.build()
        (client.dispatcher.maxRequestsPerHost > 5) shouldBe true
    }
})
