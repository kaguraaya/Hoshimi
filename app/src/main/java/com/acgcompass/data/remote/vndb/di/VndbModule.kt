package com.acgcompass.data.remote.vndb.di

import com.acgcompass.core.network.NetworkConstants
import com.acgcompass.data.remote.vndb.VndbApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

/**
 * VNDB 接入 Hilt 模块。
 *
 * 复用 `NetworkModule` 提供的共享 [Retrofit.Builder]（已绑定基础 OkHttp 与 kotlinx.serialization
 * 转换器、拦截器链：UA / Auth / RateLimit / Timeout），补 [NetworkConstants.VNDB_BASE_URL] 后
 * 构建 [VndbApi]。可选 token 由 `SourceAuths.vndb` 标签触发 `AuthInterceptor` 注入
 * `Authorization: Token <token>`（无凭据时透传匿名查询）。
 *
 * [com.acgcompass.data.remote.vndb.VndbRemoteDataSource] 通过构造注入（`@Inject` + `@Singleton`）
 * 自动可用，无需在此显式 `@Provides`。
 */
@Module
@InstallIn(SingletonComponent::class)
object VndbModule {

    @Provides
    @Singleton
    fun provideVndbApi(retrofitBuilder: Retrofit.Builder): VndbApi = retrofitBuilder
        .baseUrl(NetworkConstants.VNDB_BASE_URL)
        .build()
        .create(VndbApi::class.java)
}
