package com.acgcompass.data.remote.anilist.di

import com.acgcompass.core.network.graphql.GraphQlClient
import com.acgcompass.data.remote.anilist.AniListApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * AniList 接入 Hilt 模块。
 *
 * 复用 `NetworkModule` 提供的 AniList [GraphQlClient]（已绑定共享 OkHttp + 拦截器链 +
 * `SourceAuths.anilist` 鉴权标签：有 token 时注入 Bearer，无 token 时透传匿名公共查询）与共享 [Json]，
 * 构建 [AniListApi]。
 *
 * [com.acgcompass.data.remote.anilist.AniListRemoteDataSource] 通过构造注入（`@Inject` + `@Singleton`）
 * 自动可用，无需在此显式 `@Provides`。
 */
@Module
@InstallIn(SingletonComponent::class)
object AniListModule {

    @Provides
    @Singleton
    fun provideAniListApi(graphQlClient: GraphQlClient, json: Json): AniListApi =
        AniListApi(client = graphQlClient, json = json)
}
