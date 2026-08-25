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
                // 官方文档目前标注「降级状态，每分钟 30 次」（恢复后为 90 次），实测响应头 X-RateLimit-Limit: 30。
                // 每分钟 45 次会撞上 429 并触发退避；取 28 次，留 2 次余量给窗口对齐误差。
                rules = listOf(RateLimiter.Rule.perSecond(1), RateLimiter.Rule.perMinute(28)),
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
                // 两个并发槽：详情页的条目 / 人物 / 角色请求可以并行；其中至多一个给后台任务。
                maxConcurrentRequests = 2,
            ),
        )
    }
}

enum class RequestPriority {
    FOREGROUND,
    BACKGROUND,
}
