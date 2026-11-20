package com.acgcompass.data.remote.ai

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.common.toAppError
import com.acgcompass.core.common.withCause
import com.acgcompass.core.network.HttpErrorMapper
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * AI provider 共享的挂起式 HTTP 执行器。
 *
 * 复用 `core/network` 的 AI 专用 [OkHttpClient]（[com.acgcompass.core.network.di.AiOkHttpClient]，
 * 宽松超时档），以 OkHttp 异步 `enqueue` + [suspendCancellableCoroutine] 实现非阻塞调用，
 * 并在协程取消时取消底层 [Call]（结构化并发友好）。
 *
 * 错误映射（不抛裸异常给上层逻辑，统一为领域错误）：
 * - 非 2xx → [HttpErrorMapper.mapStatusCode]（401/403→Unauthorized、429→RateLimited、5xx→Server…）。
 * - 空响应体 → [AppError.FieldMissing]。
 * - I/O / 超时 / 无网络 → [Throwable.toAppError]（→ `Network`）。
 * 所有错误均以 [com.acgcompass.core.common.AppErrorException] 抛出，便于 `runCatchingApp` 还原。
 */
class AiHttpCaller(
    private val client: OkHttpClient,
) {

    /**
     * 执行 [request] 并返回成功响应体文本。
     *
     * @throws com.acgcompass.core.common.AppErrorException 见类级错误映射说明。
     */
    suspend fun execute(request: Request): String = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { runCatchingCancellable { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e.toAppError().asException())
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val body = runCatchingCancellable { resp.body?.string() }.getOrNull().orEmpty()
                    when {
                        !resp.isSuccessful ->
                            cont.resumeWithException(
                                // 保留状态码映射的领域类型，并把服务端错误说明（如
                                // 「model not found」/ 配额 / 鉴权说明）注入 cause，便于设置页连接
                                // 测试与 AI 调用直接展示「为什么失败」，而非泛化文案。
                                HttpErrorMapper.mapStatusCode(resp.code)
                                    .withCause(httpErrorDetail(resp.code, body))
                                    .asException(),
                            )

                        body.isBlank() ->
                            cont.resumeWithException(AppError.FieldMissing().asException())

                        else -> cont.resume(body)
                    }
                }
            }
        })
    }

    /**
     * 从非 2xx 响应体提取可读失败原因：优先解析 OpenAI 兼容错误体的 `message` 字段，
     * 否则截断原始体；附带 HTTP 状态码。响应体不含凭据（key 仅在请求头），可安全展示。
     */
    private fun httpErrorDetail(code: Int, body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return "HTTP $code（无响应体）"
        val message = MESSAGE_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
            ?.replace("\\n", " ")
            ?.replace("\\\"", "\"")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val detail = message ?: trimmed.replace(Regex("\\s+"), " ").take(MAX_ERROR_BODY_CHARS)
        return "HTTP $code：$detail"
    }

    private companion object {
        /** 错误体截断上限（避免超长 HTML/JSON 刷屏）。 */
        const val MAX_ERROR_BODY_CHARS = 300

        /** 提取 OpenAI 兼容错误体的 `"message": "..."`（兼容转义引号）。 */
        val MESSAGE_REGEX = Regex("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    }
}
