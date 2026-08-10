package com.acgcompass.core.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * REST 客户端工厂：用 OkHttp + kotlinx.serialization 组装 [Retrofit]。
 *
 * 各源（Bangumi / Jikan / MAL / VNDB）拥有不同 Base URL，因此对外暴露一个共享的
 * [Retrofit.Builder]（已绑定 client 与 Json 转换器），由各源模块 `.baseUrl(...).build()`
 * 后 `create(Service::class)`，避免重复装配转换器。
 */
object RetrofitFactory {

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    /**
     * 返回一个已配置好 OkHttp 调用器与 kotlinx.serialization 转换器的 [Retrofit.Builder]。
     * 调用方补充 `baseUrl(...)` 后 `build()`。
     */
    fun builder(client: OkHttpClient, json: Json = NetworkJson.instance): Retrofit.Builder = Retrofit.Builder()
        .client(client)
        .addConverterFactory(json.asConverterFactory(JSON_MEDIA_TYPE))

    /** 便捷方法：直接为指定 [baseUrl] 构建 [Retrofit] 实例。 */
    fun create(baseUrl: String, client: OkHttpClient, json: Json = NetworkJson.instance): Retrofit =
        builder(client, json)
            .baseUrl(baseUrl)
            .build()
}
