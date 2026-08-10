package com.acgcompass.data.remote.bangumi.di

import com.acgcompass.BuildConfig
import com.acgcompass.core.network.NetworkConstants
import com.acgcompass.data.remote.bangumi.BangumiApi
import com.acgcompass.data.remote.bangumi.BangumiNextApi
import com.acgcompass.data.remote.bangumi.BangumiOAuthApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import retrofit2.Retrofit

/**
 * Bangumi 接入 Hilt 模块。
 *
 * 复用 `NetworkModule` 提供的共享 [Retrofit.Builder]（已绑定基础 OkHttp 与 kotlinx.serialization
 * 转换器、拦截器链），补 [NetworkConstants.BANGUMI_BASE_URL] 后构建 [BangumiApi]。
 *
 * [com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource] 通过构造注入（`@Inject` + `@Singleton`）
 * 自动可用，无需在此显式 `@Provides`。
 */
@Module
@InstallIn(SingletonComponent::class)
object BangumiModule {

    @Provides
    @Singleton
    fun provideBangumiApi(retrofitBuilder: Retrofit.Builder): BangumiApi = retrofitBuilder
        .baseUrl(NetworkConstants.BANGUMI_BASE_URL)
        .build()
        .create(BangumiApi::class.java)

    /**
     * Bangumi next.bgm.tv/p1 公共短评接口。复用同一 [Retrofit.Builder]（含 UA / 超时拦截），
     * 仅换 base url；公共数据无需鉴权（接口方法不带 `@Tag`）。
     */
    @Provides
    @Singleton
    fun provideBangumiNextApi(retrofitBuilder: Retrofit.Builder): BangumiNextApi = retrofitBuilder
        .baseUrl(NetworkConstants.BANGUMI_NEXT_BASE_URL)
        .build()
        .create(BangumiNextApi::class.java)

    /**
     * Bangumi OAuth2 令牌端点（`bgm.tv/oauth/access_token`）。复用同一 [Retrofit.Builder]
     * （含 UA / 超时拦截）；令牌请求不带 Bangumi `@Tag`，故不被鉴权 / 反代改写拦截器处理。
     */
    @Provides
    @Singleton
    fun provideBangumiOAuthApi(retrofitBuilder: Retrofit.Builder): BangumiOAuthApi = retrofitBuilder
        .baseUrl(NetworkConstants.BANGUMI_OAUTH_BASE_URL)
        .build()
        .create(BangumiOAuthApi::class.java)

    /**
     * 内置 Bangumi OAuth **应用凭据**（非用户凭据），来自 gitignored 的 local.properties
     * → [BuildConfig]。发布应用共用该 OAuth 客户端；缺省空串时回退到用户自填配置。
     */
    @Provides
    @Named("bangumiDefaultClientId")
    fun provideBangumiDefaultClientId(): String = BuildConfig.BANGUMI_CLIENT_ID

    @Provides
    @Named("bangumiDefaultClientSecret")
    fun provideBangumiDefaultClientSecret(): String = BuildConfig.BANGUMI_CLIENT_SECRET

    @Provides
    @Named("bangumiOAuthProxyBaseUrl")
    fun provideBangumiOAuthProxyBaseUrl(): String = BuildConfig.BANGUMI_OAUTH_PROXY_BASE_URL
}
