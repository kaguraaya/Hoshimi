package com.acgcompass.core.network.di

import com.acgcompass.BuildConfig
import com.acgcompass.core.network.BangumiNetworkConfigProvider
import com.acgcompass.core.network.NetworkCredentialProvider
import com.acgcompass.core.network.interceptor.AuthInterceptor
import com.acgcompass.core.network.interceptor.BangumiBaseUrlInterceptor
import com.acgcompass.core.network.interceptor.RateLimitInterceptor
import com.acgcompass.core.network.interceptor.RateLimiterRegistry
import com.acgcompass.core.network.interceptor.TimeoutInterceptor
import com.acgcompass.core.network.interceptor.UserAgentInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 拦截器链 Hilt 模块。
 *
 * 以单例提供四个拦截器与每源限流注册表，供 [NetworkModule] 按
 * `UserAgent → Auth → RateLimit → Timeout` 顺序安装到 REST / AI 的 [okhttp3.OkHttpClient]：
 * - [UserAgentInterceptor]：注入 `ACGCompass/{versionName}` 合规 UA。
 * - [AuthInterceptor]：按请求标注的源从 [NetworkCredentialProvider] 注入鉴权，无凭据透传。
 * - [RateLimitInterceptor]：每源独立限流，接近预算上限时主动错峰。
 * - [TimeoutInterceptor]：强化 10s 超时。
 *
 * 不在此处硬编码任何 key/token；版本号取自 [BuildConfig.VERSION_NAME]。
 */
@Module
@InstallIn(SingletonComponent::class)
object InterceptorModule {

    @Provides
    @Singleton
    fun provideUserAgentInterceptor(): UserAgentInterceptor = UserAgentInterceptor(
        userAgent = UserAgentInterceptor.defaultUserAgent(BuildConfig.VERSION_NAME),
    )

    @Provides
    @Singleton
    fun provideAuthInterceptor(credentialProvider: NetworkCredentialProvider): AuthInterceptor =
        AuthInterceptor(credentialProvider)

    @Provides
    @Singleton
    fun provideBangumiBaseUrlInterceptor(configProvider: BangumiNetworkConfigProvider): BangumiBaseUrlInterceptor =
        BangumiBaseUrlInterceptor(configProvider)

    @Provides
    @Singleton
    fun provideRateLimiterRegistry(): RateLimiterRegistry = RateLimiterRegistry.default()

    @Provides
    @Singleton
    fun provideRateLimitInterceptor(registry: RateLimiterRegistry): RateLimitInterceptor =
        RateLimitInterceptor(registry)

    @Provides
    @Singleton
    fun provideTimeoutInterceptor(): TimeoutInterceptor = TimeoutInterceptor()
}
