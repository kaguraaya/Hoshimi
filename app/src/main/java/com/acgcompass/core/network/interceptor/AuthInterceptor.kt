package com.acgcompass.core.network.interceptor

import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.network.NetworkCredential
import com.acgcompass.core.network.NetworkCredentialProvider
import com.acgcompass.core.network.NetworkSource
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 鉴权注入拦截器。
 *
 * 流程：从请求上标注的 [SourceAuth]（见 [networkSource]）识别目标源 → 从 [NetworkCredentialProvider]
 * 读取该源的明文凭据 → 由 [SourceAuth] 策略注入对应鉴权头/参数。
 *
 * 行为约束：
 * - **无标注**：请求未标注 [SourceAuth] 时原样透传（公共请求 / 未接入源）。
 * - **无凭据**：该源未配置或读取失败时原样透传（「公共查询免鉴权」）。
 * - **安全**：凭据仅在内存中用于注入，**绝不**记录日志或缓存于本类。
 *
 * 实现说明：OkHttp 拦截器是同步 API，而 [NetworkCredentialProvider.get] 是挂起函数；拦截器运行在
 * OkHttp 的后台分发线程，这里用 [runBlocking] 在该线程上等待读取凭据是可接受的，不阻塞主线程。
 */
class AuthInterceptor(
    private val credentialProvider: NetworkCredentialProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val auth = request.sourceAuth() ?: return chain.proceed(request)

        val secret: NetworkCredential? = readSecret(auth.sourceId)
        val authenticated = auth.authenticate(request, secret)
        val response = chain.proceed(authenticated)
        if (secret != null && !authenticated.header("Authorization").isNullOrBlank() &&
            response.code in UNAUTHORIZED_STATUS_CODES
        ) {
            runCatchingCancellable {
                runBlocking { credentialProvider.onUnauthorized(auth.sourceId) }
            }
        }
        return response
    }

    /** 读取指定源凭据；任何异常都降级为「无凭据」以保证不崩溃。 */
    private fun readSecret(sourceId: NetworkSource): NetworkCredential? =
        runCatchingCancellable { runBlocking { credentialProvider.get(sourceId) } }.getOrNull()

    private companion object {
        val UNAUTHORIZED_STATUS_CODES = setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN)
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
    }
}
