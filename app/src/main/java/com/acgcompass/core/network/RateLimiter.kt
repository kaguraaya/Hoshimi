package com.acgcompass.core.network

import kotlin.math.ceil

/**
 * 每个数据源独立的速率限制器。
 *
 * 采用**滑动窗口日志（sliding-window log）**算法，支持对同一源配置**多个并存的窗口规则**
 * （双桶 / 多桶），例如 Jikan 同时满足「1 req/5s」**且**「10 req/min」。其设计目标是任意时刻、
 * 任意窗口内放行的请求数都不超过配置上限。
 *
 * 设计要点：
 * - **纯逻辑、无 Android / 协程依赖**，便于在 JVM 单元 / 属性测试中直接验证。
 * - 时间源 [clock] 可注入（默认使用单调时钟 `System.nanoTime`），测试可注入受控时钟。
 * - [reserve] 是**确定性**的：给定相同的调用序列与时钟读数，产出相同的放行时间序列；它只做
 *   记账并返回「该请求被允许发出的时间戳」，不自行阻塞，便于属性测试断言窗口约束。
 * - [acquireBlocking] 在拦截器线程内按需 `Thread.sleep` 至放行时刻；OkHttp 拦截器运行在
 *   后台调度线程，阻塞等待是可接受的（见 [com.acgcompass.core.network.interceptor.RateLimitInterceptor]）。
 * - **主动节流**：当某窗口内的占用达到上限的 [throttleThreshold]（默认 80%）时，
 *   对后续请求按 `窗口/上限` 的间隔均匀错峰，避免「先突发打满再被硬限流」。
 */
@Suppress("TooManyFunctions")
class RateLimiter(
    rules: List<Rule>,
    private val throttleThreshold: Double = DEFAULT_THROTTLE_THRESHOLD,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
) {

    /**
     * 单条窗口规则：在任意长度为 [windowMillis] 的滑动窗口内，最多放行 [maxRequests] 个请求。
     */
    data class Rule(val maxRequests: Int, val windowMillis: Long) {
        init {
            require(maxRequests > 0) { "maxRequests must be > 0, was $maxRequests" }
            require(windowMillis > 0) { "windowMillis must be > 0, was $windowMillis" }
        }

        companion object {
            /** 每秒 [n] 次。 */
            fun perSecond(n: Int): Rule = Rule(n, 1_000L)

            /** 每分钟 [n] 次。 */
            fun perMinute(n: Int): Rule = Rule(n, 60_000L)
        }
    }

    init {
        require(rules.isNotEmpty()) { "RateLimiter requires at least one Rule" }
        require(throttleThreshold in 0.0..1.0) {
            "throttleThreshold must be within [0,1], was $throttleThreshold"
        }
    }

    private val rules: List<Rule> = rules.sortedByDescending { it.windowMillis }
    private val longestWindowMillis: Long = this.rules.first().windowMillis

    /** 已放行（含未来预约）的时间戳，按升序保存；最多保留最长窗口内的记录。 */
    private val grants = ArrayDeque<Long>()
    private var pausedUntilMillis: Long = Long.MIN_VALUE

    /**
     * 预约一个放行名额并返回其被允许发出的时间戳（≥ 当前时钟读数）。
     *
     * 该方法保证：对于每条规则，加入本次放行后，任意长度为 `windowMillis` 的窗口内的放行数
     * 都不超过 `maxRequests`。线程安全（整体同步）。
     */
    @Synchronized
    fun reserve(): Long {
        val now = clock()
        prune(now)

        var grant = maxOf(now, pausedUntilMillis)
        // 放行时间单调不减：保证 grants 始终有序，并使「未来预约」自然推迟后续请求。
        grants.lastOrNull()?.let { last -> if (last > grant) grant = last }

        for (rule in rules) {
            grant = maxOf(grant, hardEarliest(rule), softEarliest(rule, grant))
        }

        grants.addLast(grant)
        return grant
    }

    /**
     * 阻塞当前线程直到获得放行名额（适用于 OkHttp 拦截器线程）。
     *
     * @param sleeper 休眠实现，默认 [Thread.sleep]；测试可注入空实现避免真实等待。
     */
    fun acquireBlocking(sleeper: (Long) -> Unit = { Thread.sleep(it) }) {
        val now = clock()
        val grant = reserve()
        val waitMillis = grant - now
        if (waitMillis > 0L) sleeper(waitMillis)
    }

    /**
     * Try to consume a permit without reserving a future slot.  The interceptor
     * uses this when a request is waiting for a source permit so that a
     * background request cannot book the only future slot and starve a
     * foreground detail request.
     */
    @Synchronized
    fun tryAcquireNow(): Boolean {
        val now = clock()
        prune(now)
        val grant = calculateGrant(now)
        if (grant > now) return false
        grants.addLast(now)
        return true
    }

    /** Milliseconds until a non-reserving attempt may be retried. */
    @Synchronized
    fun delayUntilNextPermitMillis(): Long {
        val now = clock()
        prune(now)
        return (calculateGrant(now) - now).coerceAtLeast(0L)
    }

    /** Prevent new reservations until at least [timestampMillis] on this limiter's clock. */
    @Synchronized
    fun pauseUntil(timestampMillis: Long) {
        pausedUntilMillis = maxOf(pausedUntilMillis, timestampMillis)
    }

    @Synchronized
    fun pauseFor(delayMillis: Long) {
        if (delayMillis > 0L) pauseUntil(clock() + delayMillis)
    }

    private fun calculateGrant(now: Long): Long {
        var grant = maxOf(now, pausedUntilMillis)
        grants.lastOrNull()?.let { last -> if (last > grant) grant = last }
        for (rule in rules) {
            grant = maxOf(grant, hardEarliest(rule), softEarliest(rule, grant))
        }
        return grant
    }

    /**
     * 硬约束：为满足 [rule] 上限，本次放行不得早于此时刻。
     *
     * 若已有放行数 < 上限，则无约束（返回 [Long.MIN_VALUE]）；否则取「倒数第 max 个」放行时间
     * 加上窗口长度——这样可确保它被挤出窗口，使窗口内（含新放行）至多 `maxRequests` 个。
     */
    private fun hardEarliest(rule: Rule): Long {
        if (grants.size < rule.maxRequests) return Long.MIN_VALUE
        val oldestOfLastMax = grantAt(grants.size - rule.maxRequests)
        return oldestOfLastMax + rule.windowMillis
    }

    /**
     * 软约束：当窗口内占用达到阈值时，按 `窗口/上限` 间隔错峰。
     *
     * 仅在「相对候选时间 [candidate] 的窗口内已达阈值占用」时生效；否则不约束。
     */
    private fun softEarliest(rule: Rule, candidate: Long): Long {
        val threshold = ceil(rule.maxRequests * throttleThreshold).toInt().coerceAtLeast(1)
        val windowStart = candidate - rule.windowMillis
        val inWindow = grants.count { it > windowStart }
        if (inWindow < threshold) return Long.MIN_VALUE
        val spacing = rule.windowMillis / rule.maxRequests
        val last = grants.lastOrNull() ?: return Long.MIN_VALUE
        return last + spacing
    }

    /** 移除不可能再落入任何窗口的历史记录（早于 `now - 最长窗口`）。 */
    private fun prune(now: Long) {
        val cutoff = now - longestWindowMillis
        while (grants.isNotEmpty() && grants.first() <= cutoff) {
            grants.removeFirst()
        }
    }

    /** 按索引访问升序 [grants]（ArrayDeque 无随机访问，做线性定位）。 */
    private fun grantAt(index: Int): Long {
        // index 较小且 grants 规模受窗口约束有限（≤ 各规则上限之和级别），线性访问可接受。
        var i = 0
        for (g in grants) {
            if (i == index) return g
            i++
        }
        throw IndexOutOfBoundsException("index=$index size=${grants.size}")
    }

    companion object {
        /** 默认主动节流阈值：达到上限的 80% 即开始错峰。 */
        const val DEFAULT_THROTTLE_THRESHOLD: Double = 0.8
    }
}
