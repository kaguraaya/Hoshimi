package com.acgcompass.data.remote.mal.di

import com.acgcompass.core.network.NetworkConstants
import com.acgcompass.data.remote.mal.MalApi
import com.acgcompass.data.remote.mal.MalOAuthApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

/**
 * MAL 官方接入 Hilt 模块。
 *
 * 复用 `NetworkModule` 提供的共享 [Retrofit.Builder]（已绑定基础 OkHttp 与 kotlinx.serialization
 * 转换器、拦截器链：UA / Auth / RateLimit / Timeout）：
 * - [MalApi]：补 [NetworkConstants.MAL_BASE_URL]（`api.myanimelist.net/v2/`，REST 读取）。
 * - [MalOAuthApi]：补 [NetworkConstants.MAL_OAUTH_BASE_URL]（`myanimelist.net/v1/oauth2/`，PKCE 令牌端点）。
 *
 * 鉴权由 `SourceAuths.mal`（`MalAuth`）标签触发 `AuthInterceptor` 注入（OAuth `Bearer` 或
 * `X-MAL-CLIENT-ID` 头）；令牌端点调用不打标签、透传（其凭据以表单字段提交）。
 * 不内置任何 client id / secret —— 仅用户在 `CredentialStore` 配置后启用。
 *
 * [com.acgcompass.data.remote.mal.MalRemoteDataSource] / [com.acgcompass.data.remote.mal.MalOAuthClient]
 * 通过构造注入（`@Inject` + `@Singleton`）自动可用，无需在此显式 `@Provides`。
 */
@Module
@InstallIn(SingletonComponent::class)
object MalModule {

    @Provides
    @Singleton
    fun provideMalApi(retrofitBuilder: Retrofit.Builder): MalApi = retrofitBuilder
        .baseUrl(NetworkConstants.MAL_BASE_URL)
        .build()
        .create(MalApi::class.java)

    @Provides
    @Singleton
    fun provideMalOAuthApi(retrofitBuilder: Retrofit.Builder): MalOAuthApi = retrofitBuilder
        .baseUrl(NetworkConstants.MAL_OAUTH_BASE_URL)
        .build()
        .create(MalOAuthApi::class.java)
}
