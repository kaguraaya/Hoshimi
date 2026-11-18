package com.acgcompass.data.sync

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import kotlinx.coroutines.delay

/** 值得马上再试一次的失败：网络中断、超时、服务端错误、限流。鉴权、404、字段缺失再试也一样。 */
internal fun AppError.isTransient(): Boolean =
    this is AppError.Network || this is AppError.Server || this is AppError.RateLimited

/**
 * 执行 [block]，遇到 [isTransient] 的失败时按 [delaysMillis] 依次等待后重试；成功或遇到非临时失败立即返回。
 * 用于同步里的单个请求：一次网络抖动不至于让整次同步失败。
 */
internal suspend fun <T> retryTransient(
    delaysMillis: List<Long> = SYNC_RETRY_DELAYS_MILLIS,
    block: suspend () -> AppResult<T>,
): AppResult<T> {
    var result = block()
    for (wait in delaysMillis) {
        val failure = result as? AppResult.Failure
        if (failure == null || !failure.error.isTransient()) break
        delay(wait)
        result = block()
    }
    return result
}

/**
 * 网络类失败换成说明线路问题的提示：Bangumi 的 API（api.bgm.tv）和网页走不同线路，
 * 网页能打开不代表 API 可用。其它错误原样返回。
 */
internal fun AppError.withBangumiApiHint(): AppError = when (this) {
    is AppError.Network -> copy(
        cause = "无法连接 Bangumi API（$cause）。API 与网页走不同线路，网页能打开不代表 API 可用；" +
            "可在「设置 → 数据源 → Bangumi → API 地址」更换",
        nextStep = "稍后重试，或在「设置 → 数据源 → Bangumi → API 地址」更换地址",
    )
    else -> this
}

/** 同步里单个请求的重试间隔：2 秒、5 秒。 */
internal val SYNC_RETRY_DELAYS_MILLIS: List<Long> = listOf(FIRST_RETRY_DELAY_MILLIS, SECOND_RETRY_DELAY_MILLIS)

private const val FIRST_RETRY_DELAY_MILLIS = 2_000L
private const val SECOND_RETRY_DELAY_MILLIS = 5_000L
