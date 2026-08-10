package com.acgcompass.core.network.di

import com.acgcompass.core.network.HttpClientFactory
import com.acgcompass.core.network.HttpTimeouts
import com.acgcompass.core.network.NetworkConstants
import com.acgcompass.core.network.NetworkJson
import com.acgcompass.core.network.RetrofitFactory
import com.acgcompass.core.network.graphql.GraphQlClient
import com.acgcompass.core.network.interceptor.AuthInterceptor
import com.acgcompass.core.network.interceptor.RateLimitInterceptor
import com.acgcompass.core.network.interceptor.SourceAuths
import com.acgcompass.core.network.interceptor.TimeoutInterceptor
import com.acgcompass.core.network.interceptor.UserAgentInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit

/**
 * 网络核心 Hilt 模块。
 *
 * 以 application 单例提供：
 * - 共享 [Json] 配置；
 * - 基础 [OkHttpClient]（REST 短超时）与 AI 专用 [OkHttpClient]（独立实例）；
 * - 预绑定转换器的 [Retrofit.Builder]（各源补 baseUrl 后构建）；
 * - AniList 的 [GraphQlClient]（薄 OkHttp GraphQL POST 调用器，见其 KDoc 决策说明）。
 *
 * 拦截器链（UserAgent / Auth / RateLimit / Timeout）由 `InterceptorModule` 提供，
 * 并在 [provideBaseOkHttpClient] / [provideAiOkHttpClient] 中按
 * `UserAgent → Auth → RateLimit → Timeout` 顺序注入到 [HttpClientFactory.build]。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = NetworkJson.instance

    @Provides
    @Singleton
    @BaseOkHttpClient
    fun provideBaseOkHttpClient(
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
        bangumiBaseUrlInterceptor: com.acgcompass.core.network.interceptor.BangumiBaseUrlInterceptor,
        userAgentInterceptor: UserAgentInterceptor,
        authInterceptor: AuthInterceptor,
        rateLimitInterceptor: RateLimitInterceptor,
        timeoutInterceptor: TimeoutInterceptor,
    ): OkHttpClient = HttpClientFactory.build(
        timeouts = HttpTimeouts.REST,
        // 顺序：UserAgent → Auth → BangumiBaseUrl → RateLimit → Timeout。
        interceptors = listOf(
            userAgentInterceptor,
            authInterceptor,
            bangumiBaseUrlInterceptor,
            rateLimitInterceptor,
            timeoutInterceptor,
        ),
        // 20MB HTTP 响应磁盘缓存，复用条目/评分/评论等 GET 响应（缓存利用）。
        cache = okhttp3.Cache(
            directory = context.cacheDir.resolve("http_cache"),
            maxSize = 20L * 1024 * 1024,
        ),
    )

    @Provides
    @Singleton
    @AiOkHttpClient
    fun provideAiOkHttpClient(
        userAgentInterceptor: UserAgentInterceptor,
        authInterceptor: AuthInterceptor,
        rateLimitInterceptor: RateLimitInterceptor,
    ): OkHttpClient = HttpClientFactory.build(
        timeouts = HttpTimeouts.AI,
        // AI 走独立超时档（不安装 10s TimeoutInterceptor）；鉴权/限流按请求标注的源透传或注入。
        interceptors = listOf(
            userAgentInterceptor,
            authInterceptor,
            rateLimitInterceptor,
        ),
    )

    /**
     * 共享的 [Retrofit.Builder]（已绑定基础 OkHttp 与 Json 转换器）。
     * 各 REST 源模块注入后补 `.baseUrl(NetworkConstants.SOURCE_BASE_URL).build()`。
     */
    @Provides
    @Singleton
    fun provideRetrofitBuilder(@BaseOkHttpClient client: OkHttpClient, json: Json): Retrofit.Builder =
        RetrofitFactory.builder(client, json)

    /** AniList GraphQL 客户端（公共查询免鉴权；有凭据时由拦截器注入可选 Bearer token）。 */
    @Provides
    @Singleton
    fun provideAniListGraphQlClient(@BaseOkHttpClient client: OkHttpClient, json: Json): GraphQlClient = GraphQlClient(
        client = client,
        serverUrl = NetworkConstants.ANILIST_GRAPHQL_URL,
        json = json,
        // 打上 AniList 鉴权标签：AuthInterceptor 在有 token 时注入 Bearer，无 token 时透传匿名查询。
        sourceAuth = SourceAuths.anilist,
    )
}
