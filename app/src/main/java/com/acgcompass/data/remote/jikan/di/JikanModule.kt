package com.acgcompass.data.remote.jikan.di

import com.acgcompass.core.network.NetworkConstants
import com.acgcompass.data.remote.jikan.JikanApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

/**
 * Jikan 接入 Hilt 模块。
 *
 * 复用 `NetworkModule` 提供的共享 [Retrofit.Builder]（已绑定基础 OkHttp 与 kotlinx.serialization
 * 转换器、拦截器链），补 [NetworkConstants.JIKAN_BASE_URL] 后构建 [JikanApi]。
 *
 * 双桶限流（1 req/5s 且 10 req/min）已在 `RateLimiterRegistry.default()` 中以 `SourceId.JIKAN`
 * 注册（见 `core/network/di/InterceptorModule`），由共享拦截器链对所有 Jikan 请求生效，
 * 无需在此重复配置。
 *
 * [com.acgcompass.data.remote.jikan.JikanRemoteDataSource] 通过构造注入（`@Inject` + `@Singleton`）
 * 自动可用，无需在此显式 `@Provides`。
 */
@Module
@InstallIn(SingletonComponent::class)
object JikanModule {

    @Provides
    @Singleton
    fun provideJikanApi(retrofitBuilder: Retrofit.Builder): JikanApi = retrofitBuilder
        .baseUrl(NetworkConstants.JIKAN_BASE_URL)
        .build()
        .create(JikanApi::class.java)
}
