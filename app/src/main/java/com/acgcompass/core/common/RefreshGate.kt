package com.acgcompass.core.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 下拉刷新的节奏控制：
 * - 真正的刷新在调用方的作用域里跑，指示器最多陪它转 [maxIndicatorMillis]；超时后指示器收起，刷新继续在后台进行，
 *   数据到了页面自己更新（页面都是观察数据库的）。用户不用盯着转圈等慢的数据源。
 * - 指示器至少转 [minIndicatorMillis]，太快收起像是没反应。
 * - [minIntervalMillis] 内再次下拉不重复请求网络，只让指示器转一小会儿作为反馈，避免连续下拉占用请求额度。
 */
class RefreshGate(
    private val minIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val maxIndicatorMillis: Long = MAX_INDICATOR_MILLIS,
    private val minIndicatorMillis: Long = MIN_INDICATOR_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastRunAt = 0L

    /**
     * 在 [scope] 里启动 [work]，挂起到它完成或到达指示器上限为止。被节流时不启动，返回 false。
     */
    suspend fun run(scope: CoroutineScope, work: suspend CoroutineScope.() -> Unit): Boolean {
        val started = clock()
        if (lastRunAt != 0L && started - lastRunAt < minIntervalMillis) {
            delay(minIndicatorMillis)
            return false
        }
        lastRunAt = started
        val job = scope.launch(block = work)
        withTimeoutOrNull(maxIndicatorMillis) { job.join() }
        val elapsed = clock() - started
        if (elapsed < minIndicatorMillis) delay(minIndicatorMillis - elapsed)
        return true
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 30_000L
        const val MAX_INDICATOR_MILLIS = 3_000L
        const val MIN_INDICATOR_MILLIS = 600L
    }
}
