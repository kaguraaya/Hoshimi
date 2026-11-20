package com.acgcompass.core.common

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException

/**
 * 携带 [AppError] 的异常包装，使一个已知的领域错误可以穿越 `throw`/`catch` 边界并被
 * [toAppError] / [runCatchingApp] 原样还原，而不会被降级成 [AppError.Server]。
 */
class AppErrorException(val error: AppError) : Exception(error.cause)

/** 便捷构造：把 [AppError] 转为可抛出的异常。 */
fun AppError.asException(): AppErrorException = AppErrorException(this)

/**
 * 将任意 [Throwable] 映射为领域 [AppError]。
 *
 * - 已包装的 [AppErrorException] 原样还原其 [AppError]；
 * - 网络相关异常（超时 / 无网络 / 连接失败）映射为 [AppError.Network]；
 * - 其余未知异常一律兜底为 [AppError.Server]（异常兜底，保证应用永不崩溃）。
 *
 * 注意：[CancellationException] 不应被本函数吞掉——调用方（[runCatchingApp]）会先行重抛。
 */
fun Throwable.toAppError(): AppError = when (this) {
    is AppErrorException -> error
    is SocketTimeoutException -> AppError.Network(cause = "请求超时")
    is UnknownHostException -> AppError.Network(cause = "无法连接到服务器，请检查网络")
    is IOException -> AppError.Network()
    else -> AppError.Server()
}

/**
 * 包装一个挂起调用，把任何 **未捕获** 异常兜底映射为 [AppError]（默认 [AppError.Server]），
 * 返回领域 [AppResult]。这是「异常兜底」的统一入口。
 *
 * 协程取消（[CancellationException]）会被重新抛出，以保证结构化并发的取消语义不被破坏。
 */
inline fun <T> runCatchingApp(block: () -> T): AppResult<T> = try {
    AppResult.Success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    AppResult.Failure(e.toAppError())
}

/**
 * 与 [runCatchingApp] 等价，但返回标准 `kotlin.Result<T>`，失败侧封装为 [AppErrorException]，
 * 供偏好使用 `kotlin.Result` 链式 API 的调用方使用。
 */
inline fun <T> runCatchingResult(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e.toAppError().asException())
}

/**
 * 和标准库 `runCatching` 一样返回 `kotlin.Result`，失败侧保留原始异常；区别是协程取消（[CancellationException]）
 * 会重新抛出，不被当成普通失败吞掉。离开页面、任务被取消时，后续代码不会继续把结果写回界面或数据库。
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
