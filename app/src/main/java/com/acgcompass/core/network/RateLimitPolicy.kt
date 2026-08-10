package com.acgcompass.core.network

/** Conservative client-side request budget for one remote source. */
data class RateLimitPolicy(
    val source: NetworkSource,
    val rules: List<RateLimiter.Rule>,
    val maxConcurrentRequests: Int = 1,
    val maxBackoffMillis: Long = 60_000L,
    val throttleThreshold: Double = RateLimiter.DEFAULT_THROTTLE_THRESHOLD,
) {
    init {
        require(rules.isNotEmpty())
        require(maxConcurrentRequests > 0)
        require(maxBackoffMillis > 0L)
        require(throttleThreshold in 0.0..1.0)
    }

    companion object {
        val DEFAULTS: List<RateLimitPolicy> = listOf(
            RateLimitPolicy(
                source = NetworkSource.TENRAI,
                rules = listOf(
                    RateLimiter.Rule.perSecond(1),
                    RateLimiter.Rule.perMinute(30),
                ),
                maxBackoffMillis = 15L * 60L * 1_000L,
                throttleThreshold = 0.5,
            ),
            RateLimitPolicy(
                source = NetworkSource.JIKAN,
                // Jikan is requested only by an explicit connection test or a foreground detail lookup.
                // The conservative cadence leaves room for server-side dynamic limits.
                rules = listOf(
                    RateLimiter.Rule(maxRequests = 1, windowMillis = 5_000L),
                    RateLimiter.Rule.perMinute(10),
                ),
                maxBackoffMillis = 15L * 60L * 1_000L,
                throttleThreshold = 0.4,
            ),
            RateLimitPolicy(
                source = NetworkSource.ANILIST,
                rules = listOf(RateLimiter.Rule.perSecond(1), RateLimiter.Rule.perMinute(45)),
            ),
            RateLimitPolicy(
                source = NetworkSource.VNDB,
                // Kana currently documents 200 requests / 5 minutes plus an execution-time budget.
                // Keep the 1 req/s burst ceiling and a stricter 30 req/min sustained budget.
                rules = listOf(RateLimiter.Rule.perSecond(1), RateLimiter.Rule.perMinute(30)),
            ),
            RateLimitPolicy(
                source = NetworkSource.MAL,
                rules = listOf(RateLimiter.Rule.perSecond(1), RateLimiter.Rule.perMinute(30)),
            ),
            RateLimitPolicy(
                source = NetworkSource.BANGUMI,
                rules = listOf(RateLimiter.Rule(5, 4_000L), RateLimiter.Rule.perMinute(45)),
            ),
        )
    }
}

enum class RequestPriority {
    FOREGROUND,
    BACKGROUND,
}
