package com.acgcompass.data.remote.jikan

import com.acgcompass.core.network.interceptor.SourceAuth
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * Jikan REST v4 接口（Base URL：
 * [com.acgcompass.core.network.NetworkConstants.JIKAN_BASE_URL]）。
 *
 * Jikan 无需鉴权（非官方 MAL 只读 API）：每个方法接受一个 [SourceAuth] 的 `@Tag` 参数
 * （调用方传 `SourceAuths.jikan`，即 `NoAuth`），主要用于让
 * [com.acgcompass.core.network.interceptor.RateLimitInterceptor] 识别请求归属的源并施加
 * **双桶限流（1 req/5s 且 10 req/min）**；合规 User-Agent 由 `core/network` 拦截器链注入。
 *
 * 所有方法返回 [Response]，以便 [JikanRemoteDataSource] 检视 HTTP 状态码并经
 * [com.acgcompass.core.network.HttpErrorMapper] 映射为领域 `AppError`（不依赖异常路径）。
 */
interface JikanApi {

    /** 搜索动画（`q` 关键字，`limit`/`page` 分页，`sfw` 过滤成人内容）。 */
    @GET("anime")
    suspend fun searchAnime(
        @Query("q") query: String,
        @Query("limit") limit: Int? = null,
        @Query("page") page: Int? = null,
        @Query("sfw") sfw: Boolean? = null,
        @Tag auth: SourceAuth,
    ): Response<JikanAnimeSearchResponseDto>

    /** 动画详情（含 MAL 评分 / 排名 / 人气）。 */
    @GET("anime/{id}")
    suspend fun getAnime(@Path("id") id: Int, @Tag auth: SourceAuth): Response<JikanAnimeResponseDto>

    /** 动画用户评论（分页）。 */
    @GET("anime/{id}/reviews")
    suspend fun getAnimeReviews(
        @Path("id") id: Int,
        @Query("page") page: Int? = null,
        @Tag auth: SourceAuth,
    ): Response<JikanReviewsResponseDto>

    /** 动画推荐（基于 MAL 社区投票）。 */
    @GET("anime/{id}/recommendations")
    suspend fun getAnimeRecommendations(
        @Path("id") id: Int,
        @Tag auth: SourceAuth,
    ): Response<JikanRecommendationsResponseDto>

    /** Top 动画公共榜单，无需 Token。 */
    @GET("top/anime")
    suspend fun getTopAnime(
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = null,
        @Tag auth: SourceAuth,
    ): Response<JikanAnimeSearchResponseDto>

    /** 本季动画公共榜单，无需 Token。 */
    @GET("seasons/now")
    suspend fun getSeasonNow(
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = null,
        @Tag auth: SourceAuth,
    ): Response<JikanAnimeSearchResponseDto>
}
