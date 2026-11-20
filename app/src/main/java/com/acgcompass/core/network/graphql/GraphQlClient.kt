package com.acgcompass.core.network.graphql

import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.core.network.interceptor.SourceAuth
import com.acgcompass.core.network.interceptor.networkSource
import com.acgcompass.core.network.interceptor.withPriority
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * GraphQL 请求体（标准 `{ query, variables, operationName }`）。
 */
@Serializable
data class GraphQlRequest(
    val query: String,
    val variables: JsonObject? = null,
    val operationName: String? = null,
)

/**
 * GraphQL 原始响应。`data` / `errors` 留给上层按各 schema 解析，
 * 这里只负责传输与状态，保持薄封装、不臆造结构。
 *
 * @param statusCode HTTP 状态码。
 * @param data GraphQL `data` 字段（可能为 null）。
 * @param errors GraphQL `errors` 字段（可能为 null / 空）。
 * @param raw 原始响应体文本，便于调试与降级处理。
 */
data class GraphQlResponse(
    val statusCode: Int,
    val data: JsonElement?,
    val errors: JsonElement?,
    val raw: String,
) {
    val isSuccessful: Boolean get() = statusCode in 200..299 && errors == null
}

/**
 * 轻量 OkHttp GraphQL POST 客户端工厂。
 *
 * 这里使用共享 [OkHttpClient] 和 kotlinx.serialization 实现轻量 GraphQL POST，
 * 以便直接控制请求优先级、来源标记和错误回退。
 *
 * 复用基础 [OkHttpClient]，因此其拦截器链（UA / Auth / RateLimit / Timeout）
 * 同样作用于 GraphQL 调用。
 *
 * @param sourceAuth 可选的单源鉴权策略（如 `SourceAuths.anilist`）。非空时每个出站请求都会被
 *   [networkSource] 打标，[com.acgcompass.core.network.interceptor.AuthInterceptor] 据此在
 *   **有凭据时**注入 `Authorization: Bearer <token>`，**无凭据时透传**（AniList 公共查询免鉴权，
 *   ）。为 `null` 时所有请求匿名发送。
 */
class GraphQlClient(
    private val client: OkHttpClient,
    private val serverUrl: String,
    private val json: Json = com.acgcompass.core.network.NetworkJson.instance,
    private val sourceAuth: SourceAuth? = null,
) {

    private val mediaType = "application/json".toMediaType()

    /** 以 raw query + 可选 variables 发起一次 GraphQL 查询。 */
    suspend fun query(
        query: String,
        variables: JsonObject? = null,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): GraphQlResponse = execute(GraphQlRequest(query = query, variables = variables), priority)

    /** 执行任意 [GraphQlRequest]。 */
    suspend fun execute(
        request: GraphQlRequest,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): GraphQlResponse {
        val bodyJson = json.encodeToString(GraphQlRequest.serializer(), request)
        val httpRequest = Request.Builder()
            .url(serverUrl)
            .post(bodyJson.toRequestBody(mediaType))
            .header("Accept", "application/json")
            .apply { sourceAuth?.let { networkSource(it.withPriority(priority)) } }
            .build()

        val response = client.newCall(httpRequest).await()
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            val root: JsonObject? = runCatchingCancellable {
                if (raw.isBlank()) null else json.parseToJsonElement(raw) as? JsonObject
            }.getOrNull()
            return GraphQlResponse(
                statusCode = resp.code,
                // JSON 中的 `null`（JsonNull）规范化为 Kotlin null，避免 `"errors":null` 被误判为有错误。
                data = root?.get("data")?.takeUnless { it is JsonNull },
                errors = root?.get("errors")?.takeUnless { it is JsonNull },
                raw = raw,
            )
        }
    }
}

/** 将 OkHttp 的异步 [Call] 适配为可取消的挂起函数。 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation {
        runCatchingCancellable { cancel() }
    }
}
