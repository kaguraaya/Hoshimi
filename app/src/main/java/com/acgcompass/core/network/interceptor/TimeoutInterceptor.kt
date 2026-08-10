package com.acgcompass.core.network.interceptor

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 超时强化拦截器。
 *
 * 单次请求的 connect / read / write 三段分别限制为 10 秒，避免某一网络阶段无限等待。
 * REST 总调用超时关闭，使来源限流队列等待与真正的网络阶段超时互不混淆。
 */
class TimeoutInterceptor(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val writeTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response = chain
        .withConnectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
        .withReadTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
        .withWriteTimeout(writeTimeoutMillis, TimeUnit.MILLISECONDS)
        .proceed(chain.request())

    companion object {
        /** REST 默认每段超时：10s。 */
        const val DEFAULT_TIMEOUT_MILLIS: Int = 10_000
    }
}
