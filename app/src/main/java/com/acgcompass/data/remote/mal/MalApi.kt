package com.acgcompass.data.remote.mal

import com.acgcompass.core.network.interceptor.SourceAuth
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * MyAnimeList 官方 API v2 接口（Base URL：
 * [com.acgcompass.core.network.NetworkConstants.MAL_BASE_URL]）。
 *
 * 鉴权：每个方法接受一个 [SourceAuth] 的 `@Tag` 参数
 * （调用方传 `SourceAuths.mal`，即 [com.acgcompass.core.network.interceptor.SourceAuths.MalAuth]）：
 * - 有用户 OAuth token → 注入 `Authorization: Bearer <token>`（可读 `@me` 私有列表）；
 * - 仅有 Client ID → 退化为 `X-MAL-CLIENT-ID` 头（`client_auth` 方案，访问公开数据）；
 * - 两者均缺失 → 透传（调用方应在未配置时直接短路，不发请求）。
 *
 * `fields` 参数为必需的字段选择器（MAL 默认不返回全部字段，见 [MalFields]）。
 *
 * 所有方法返回 [Response]，以便 [MalRemoteDataSource] 检视 HTTP 状态码并经
 * [com.acgcompass.core.network.HttpErrorMapper] 映射为领域 `AppError`（不依赖异常路径）。
 */
interface MalApi {

    /** 动画详情（含 MAL 评分 `mean` / 排名 / 人气 / 集数 / 首播季）。 */
    @GET("anime/{anime_id}")
    suspend fun getAnime(
        @Path("anime_id") animeId: Int,
        @Query("fields") fields: String = MalFields.ANIME_DETAIL,
        @Tag auth: SourceAuth,
    ): Response<MalAnimeDto>

    /** 搜索动画（`q` 关键字，`limit` 上限 100 / `offset` 分页）。 */
    @GET("anime")
    suspend fun searchAnime(
        @Query("q") query: String,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("fields") fields: String = MalFields.ANIME_DETAIL,
        @Tag auth: SourceAuth,
    ): Response<MalAnimeListResponseDto>

    /**
     * 用户动画列表（官方用户列表 / 进度 / 评分）。
     *
     * @param userName 用户名或 `@me`（`@me` 需用户 OAuth token + `write:users` 作用域）。
     * @param status 可选状态过滤（watching/completed/on_hold/dropped/plan_to_watch）。
     */
    @GET("users/{user_name}/animelist")
    suspend fun getUserAnimeList(
        @Path("user_name") userName: String,
        @Query("status") status: String? = null,
        @Query("sort") sort: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Query("fields") fields: String = MalFields.USER_LIST,
        @Tag auth: SourceAuth,
    ): Response<MalUserAnimeListResponseDto>
}
