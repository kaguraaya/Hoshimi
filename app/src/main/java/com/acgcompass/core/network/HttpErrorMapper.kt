package com.acgcompass.core.network

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.toAppError

/**
 * HTTP 状态码 / 异常 → 领域 [AppError] 的**纯函数、确定且全（total）**映射器
 * 。
 *
 * 设计目标（design ：HTTP 状态到错误类型映射，Validates , 3.9）：
 * - **确定性**：同一输入永远产出同一类型的 [AppError]；
 * - **全覆盖**：任意 `Int` 状态码 / 任意 [Throwable] 都有定义，绝不抛出未捕获异常；
 * - **不伪造**：HTTP 200 但响应体为空数组 / 缺关键字段时，映射为 [AppError.FieldMissing]
 *   （对应 `UiState.PartialMissing`，UI 显示「暂无数据」）。
 *
 * 异常侧不重复造轮子：[mapThrowable] 委托给 `core/common` 已有的 [toAppError]，
 * 保持「网络异常 → Network、其余兜底 → Server、已包装的 AppErrorException 原样还原」语义一致。
 */
object HttpErrorMapper {

    /**
     * 把 HTTP 状态码映射为 [AppError]。
     *
     * - 2xx（含 200）：状态码层面视为成功，但**响应内容**可能为空/缺字段。状态码本身无法表达
     *   这一点，调用方应在解析后用 [mapEmptyOrMissing] 显式判定；这里把 2xx 一律映射为
     *   [AppError.FieldMissing]，表示「请求成功但需进一步校验内容是否完整」——这也满足
     *   「200 + 空数组/缺字段 → FieldMissing」的要求。
     * - 401 / 403 → [AppError.Unauthorized]
     * - 404 → [AppError.NotFound]
     * - 429 → [AppError.RateLimited]
     * - 5xx（含 500）→ [AppError.Server]
     * - 其它（如 3xx、其余 4xx、超出范围的码）→ [AppError.Server]（兜底）
     *
     * 该函数为纯函数，对任意 `Int` 都有定义，绝不抛出。
     */
    fun mapStatusCode(code: Int): AppError = when (code) {
        in 200..299 -> AppError.FieldMissing()
        401, 403 -> AppError.Unauthorized()
        404 -> AppError.NotFound()
        429 -> AppError.RateLimited()
        in 500..599 -> AppError.Server()
        else -> AppError.Server()
    }

    /**
     * 把任意 [Throwable] 映射为 [AppError]，委托 `core/common` 的 [toAppError]。
     *
     * - SocketTimeout / UnknownHost / 其它 IOException → [AppError.Network]
     * - 已包装的 `AppErrorException` → 原样还原其 [AppError]
     * - 其余未捕获异常 → [AppError.Server]（兜底）
     *
     * 绝不抛出未捕获异常（[toAppError] 自身为全映射）。
     */
    fun mapThrowable(t: Throwable): AppError = t.toAppError()

    /**
     * 内容层判定：HTTP 200 但解析结果为空（空数组）或缺失关键字段时，
     * 映射为 [AppError.FieldMissing]。
     *
     * @param isEmptyOrMissing 调用方解析后给出的判定（true 表示空结果/缺字段）。
     */
    fun mapEmptyOrMissing(isEmptyOrMissing: Boolean): AppError? =
        if (isEmptyOrMissing) AppError.FieldMissing() else null
}

/** 顶层便捷函数：等价于 [HttpErrorMapper.mapStatusCode]。 */
fun mapHttpError(code: Int): AppError = HttpErrorMapper.mapStatusCode(code)

/** 顶层便捷函数：等价于 [HttpErrorMapper.mapThrowable]。 */
fun mapHttpError(t: Throwable): AppError = HttpErrorMapper.mapThrowable(t)
