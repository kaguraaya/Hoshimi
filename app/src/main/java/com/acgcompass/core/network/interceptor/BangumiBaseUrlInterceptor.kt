package com.acgcompass.core.network.interceptor

import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.network.BangumiNetworkConfigProvider
import com.acgcompass.core.network.NetworkSource
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Bangumi API 地址动态改写拦截器，并负责非官方地址的 Token 隐私保护。
 *
 * Retrofit 的 baseUrl 在构建期固定为官方地址；本拦截器在请求期对**标注为 Bangumi 源**的请求，
 * 按 [BangumiNetworkConfigProvider] 提供的 API Base URL（官方 / 社区反代 / 自定义）
 * 改写 scheme/host/port，从而让**所有** Bangumi 功能（搜索 / 详情 / getMe / 收藏同步 / 连接测试 …）
 * 统一使用同一个配置地址。
 *
 * 当目标为非官方地址且用户未确认风险时，移除请求的 `Authorization` 头，
 * 确保个人 Token 绝不在未同意的情况下发往第三方（公共搜索仍可匿名工作）。因此本拦截器需排在
 * [AuthInterceptor] **之后**（先注入再按需移除）。
 *
 * 读取配置用 `runBlocking`（运行在 OkHttp 后台分发线程，不阻塞主线程，与 [AuthInterceptor] 同模式）。
 */
class BangumiBaseUrlInterceptor(
    private val configProvider: BangumiNetworkConfigProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 仅作用于 Bangumi 源请求。
        if (request.sourceAuth()?.sourceId != NetworkSource.BANGUMI) {
            return chain.proceed(request)
        }
        val config = runCatchingCancellable {
            runBlocking { configProvider.get() }
        }.getOrNull() ?: return chain.proceed(request)

        val configured = config.baseUrl.takeIf { it.isNotBlank() }
            ?: return chain.proceed(request)
        val base = configured.toHttpUrlOrNull() ?: return chain.proceed(request)

        var builder = request.newBuilder()
        val original = request.url
        val needsRewrite = original.host != base.host ||
            original.scheme != base.scheme ||
            original.port != base.port
        if (needsRewrite) {
            builder = builder.url(
                original.newBuilder()
                    .scheme(base.scheme)
                    .host(base.host)
                    .port(base.port)
                    .build(),
            )
        }
        // 非官方地址且未确认风险 → 移除 Authorization（不向第三方发送个人 Token）。
        if (!config.official && !config.allowTokenOnNonOfficial) {
            builder = builder.removeHeader("Authorization")
        }
        return chain.proceed(builder.build())
    }
}
