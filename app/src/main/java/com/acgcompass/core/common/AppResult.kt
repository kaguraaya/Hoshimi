package com.acgcompass.core.common

/**
 * 领域层通用 Result 包装：要么是 [Success]（携带数据），要么是 [Failure]（携带 [AppError]）。
 *
 * 相较于 `kotlin.Result`，本类型把失败固定为领域错误 [AppError]，使「错误一定可被映射到
 * UiState / ErrorCard」成为类型层面的约束。
 */
sealed interface AppResult<out T> {
    data class Success<out T>(val data: T) : AppResult<T>
    data class Failure(val error: AppError) : AppResult<Nothing>
}

/** 成功时返回数据，失败时返回 null。 */
fun <T> AppResult<T>.getOrNull(): T? = when (this) {
    is AppResult.Success -> data
    is AppResult.Failure -> null
}

/** 失败时返回 [AppError]，成功时返回 null。 */
fun <T> AppResult<T>.errorOrNull(): AppError? = when (this) {
    is AppResult.Success -> null
    is AppResult.Failure -> error
}

/** 对成功值做映射，失败原样透传。 */
inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Failure -> this
}

/** 成功时执行副作用。 */
inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

/** 失败时执行副作用。 */
inline fun <T> AppResult<T>.onFailure(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Failure) action(error)
    return this
}
