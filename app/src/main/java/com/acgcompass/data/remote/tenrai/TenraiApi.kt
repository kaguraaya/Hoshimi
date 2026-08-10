package com.acgcompass.data.remote.tenrai

import com.acgcompass.core.network.interceptor.SourceAuth
import com.acgcompass.data.remote.jikan.JikanAnimeResponseDto
import com.acgcompass.data.remote.jikan.JikanAnimeSearchResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/** Verified Jikan-compatible Tenrai endpoints used by Hoshimi. */
interface TenraiApi {
    @GET("anime")
    suspend fun searchAnime(
        @Query("q") query: String,
        @Query("limit") limit: Int? = null,
        @Query("page") page: Int? = null,
        @Query("sfw") sfw: Boolean? = null,
        @Tag auth: SourceAuth,
    ): Response<JikanAnimeSearchResponseDto>

    @GET("anime/{id}")
    suspend fun getAnime(@Path("id") id: Int, @Tag auth: SourceAuth): Response<JikanAnimeResponseDto>

    @GET("top/anime")
    suspend fun getTopAnime(
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = null,
        @Tag auth: SourceAuth,
    ): Response<JikanAnimeSearchResponseDto>

    @GET("seasons/now")
    suspend fun getSeasonNow(
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = null,
        @Tag auth: SourceAuth,
    ): Response<JikanAnimeSearchResponseDto>
}
