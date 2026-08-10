package com.acgcompass.core.network

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * 一组超时配置（秒）。不同用途使用不同档位：REST 数据源对连接、读写阶段使用短超时，
 * AI provider 走独立、更宽松的超时（流式 / 长补全），互不影响。
 */
data class HttpTimeouts(
    val connectSeconds: Long,
    val readSeconds: Long,
    val writeSeconds: Long,
    /** 端到端调用超时；0 表示不限制（仅 AI 流式等特殊场景考虑）。 */
    val callSeconds: Long,
) {
    companion object {
        /**
         * REST 数据源默认档。总调用超时关闭，因为来源限流排队发生在应用拦截器中；连接、读、写
         * 仍分别限制为 10 秒，排队等待不会再被误报为网络阶段超时。
         */
        val REST = HttpTimeouts(connectSeconds = 10, readSeconds = 10, writeSeconds = 10, callSeconds = 0)

        /** AI provider 档：补全可能较慢，放宽读取与端到端超时。 */
        val AI = HttpTimeouts(connectSeconds = 15, readSeconds = 60, writeSeconds = 30, callSeconds = 90)
    }
}

/**
 * 共享的 [OkHttpClient] 构建工厂。
 *
 * - 提供合理的连接 / 读 / 写 / 调用超时。
 * - 支持按数据源注入 UserAgent、鉴权、限流和超时拦截器。
 * - AI provider 使用 [HttpTimeouts.AI] 档构建 **独立实例**（不同超时 / 重试），与 REST 实例隔离。
 *
 * 通过共享底层连接池 / 线程池可进一步优化，但为隔离不同重试语义，这里按用途分别构建命名实例，
 * 由 DI（`NetworkModule`）以单例形式管理生命周期。
 */
object HttpClientFactory {

    /**
     * 构建一个命名 [OkHttpClient]。
     *
     * @param timeouts 超时档位（默认 REST）。
     * @param interceptors 应用层拦截器（按加入顺序生效）； 在此注入拦截器链。
     * @param networkInterceptors 网络层拦截器（如日志），可选。
     * @param retryOnConnectionFailure 连接失败是否自动重试。
     */
    fun build(
        timeouts: HttpTimeouts = HttpTimeouts.REST,
        interceptors: List<Interceptor> = emptyList(),
        networkInterceptors: List<Interceptor> = emptyList(),
        retryOnConnectionFailure: Boolean = true,
        cache: okhttp3.Cache? = null,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeouts.connectSeconds, TimeUnit.SECONDS)
            .readTimeout(timeouts.readSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeouts.writeSeconds, TimeUnit.SECONDS)
            .callTimeout(timeouts.callSeconds, TimeUnit.SECONDS)
            .retryOnConnectionFailure(retryOnConnectionFailure)

        // 可选 HTTP 响应磁盘缓存，复用条目/评分/评论等 GET 响应，减少重复请求（缓存利用）。
        cache?.let(builder::cache)

        interceptors.forEach(builder::addInterceptor)
        networkInterceptors.forEach(builder::addNetworkInterceptor)

        return builder.build()
    }
}
