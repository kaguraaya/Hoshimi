package com.acgcompass.core.network

import com.acgcompass.core.network.interceptor.RateLimiterRegistry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
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
        rules(NetworkSource.ANILIST) shouldBe listOf(RateLimiter.Rule.perSecond(1), RateLimiter.Rule.perMinute(45))
        rules(NetworkSource.VNDB) shouldBe listOf(RateLimiter.Rule.perSecond(1), RateLimiter.Rule.perMinute(30))
        rules(NetworkSource.MAL) shouldBe listOf(RateLimiter.Rule.perSecond(1), RateLimiter.Rule.perMinute(30))
        rules(NetworkSource.BANGUMI) shouldBe listOf(
            RateLimiter.Rule(maxRequests = 5, windowMillis = 4_000L),
            RateLimiter.Rule.perMinute(45),
        )
        policies.values.all { it.maxConcurrentRequests == 1 } shouldBe true
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
})
