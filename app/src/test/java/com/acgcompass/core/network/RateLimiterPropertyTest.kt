package com.acgcompass.core.network

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll

/**
 *
 * 校验 [RateLimiter] 的核心安全约束：对任意请求序列与到达模式，
 * 限流器放行的请求数在**任意**长度为 `windowMillis` 的滑动窗口内都不超过对应规则的上限。
 * 具体覆盖单桶（如 3 req/s）与双桶（Jikan：3 req/s 且 60 req/min）配置。
 *
 * 测试通过受控/虚拟时钟注入 [RateLimiter]，反复调用确定性的 [RateLimiter.reserve] 收集放行
 * 时间戳，再对每一个长度为 1000ms / 60000ms 的滑动窗口断言放行数不超过上限。
 *
 */
class RateLimiterPropertyTest : StringSpec({

    // iterations >= 100：覆盖足够多的随机请求数量与到达间隔模式。
    val config = PropTestConfig(iterations = 100)

    /**
     * 返回任意长度为 [windowMillis] 的滑动窗口内，[grants]（升序）中落入的最大放行数。
     *
     * 与 [RateLimiter] 的窗口语义一致：窗口为半开区间 `(g - windowMillis, g]`，即「跨度严格
     * 小于 windowMillis」的最大元素数。用双指针在 O(n) 内求解。
     */
    fun maxInAnyWindow(grants: List<Long>, windowMillis: Long): Int {
        var max = 0
        var start = 0
        for (end in grants.indices) {
            while (grants[end] - grants[start] >= windowMillis) start++
            max = maxOf(max, end - start + 1)
        }
        return max
    }

    /** 断言放行时间戳单调不减（窗口断言的前提，也是限流器的不变量）。 */
    fun assertMonotonic(grants: List<Long>) {
        grants.zipWithNext().forEach { (a, b) -> (b >= a) shouldBe true }
    }

    /**
     * 用受控时钟驱动 [RateLimiter]：依次按 [arrivalGaps] 推进虚拟时钟（毫秒），每推进一次预约一个
     * 名额，收集放行时间戳。`arrivalGaps` 为空时不产生任何放行。
     */
    fun driveWithControlledClock(rules: List<RateLimiter.Rule>, arrivalGaps: List<Int>): List<Long> {
        var nowMillis = 0L
        val limiter = RateLimiter(rules = rules, clock = { nowMillis })
        val grants = ArrayList<Long>(arrivalGaps.size)
        for (gap in arrivalGaps) {
            nowMillis += gap.toLong()
            grants.add(limiter.reserve())
        }
        return grants
    }

    // 到达间隔：0..2000ms，混合突发（0ms 同时到达）与稀疏到达，覆盖窗口边界附近的行为。
    val arrivalGaps: Arb<List<Int>> = Arb.list(Arb.int(0..2_000), range = 0..120)

    ".A 单桶限流：任意请求序列在任意 1s 窗口内放行数不超过每秒上限" {
        checkAll(config, Arb.int(1..10), arrivalGaps) { perSecond, gaps ->
            val grants = driveWithControlledClock(listOf(RateLimiter.Rule.perSecond(perSecond)), gaps)
            assertMonotonic(grants)
            (maxInAnyWindow(grants, 1_000L) <= perSecond) shouldBe true
        }
    }

    ".B 双桶限流（Jikan 3 req/s 且 60 req/min）：任意窗口内均不超过各自上限" {
        val jikanRules = listOf(
            RateLimiter.Rule.perSecond(3),
            RateLimiter.Rule.perMinute(60),
        )
        checkAll(config, arrivalGaps) { gaps ->
            val grants = driveWithControlledClock(jikanRules, gaps)
            assertMonotonic(grants)
            // 每个 1s 窗口 ≤ 3，且每个 60s 窗口 ≤ 60，两条规则必须同时成立。
            (maxInAnyWindow(grants, 1_000L) <= 3) shouldBe true
            (maxInAnyWindow(grants, 60_000L) <= 60) shouldBe true
        }
    }

    ".C 固定时钟下的突发：N 个同时到达的请求被错峰，任意窗口内仍不超限" {
        // 时钟固定为 0（所有请求同一瞬间到达），limiter 必须将放行排到未来，确保窗口约束成立。
        checkAll(config, Arb.int(1..8), Arb.int(0..150)) { perSecond, requestCount ->
            var nowMillis = 0L
            val limiter = RateLimiter(
                rules = listOf(
                    RateLimiter.Rule.perSecond(perSecond),
                    RateLimiter.Rule.perMinute(perSecond * 20),
                ),
                clock = { nowMillis },
            )
            val grants = (0 until requestCount).map { limiter.reserve() }
            assertMonotonic(grants)
            (maxInAnyWindow(grants, 1_000L) <= perSecond) shouldBe true
            (maxInAnyWindow(grants, 60_000L) <= perSecond * 20) shouldBe true
        }
    }
})
