package com.acgcompass.data.remote.bangumi

import com.acgcompass.core.network.interceptor.SourceAuth
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Tag

/**
 * Bangumi `/v0/` REST 接口（3.2，Base URL：[com.acgcompass.core.network.NetworkConstants.BANGUMI_BASE_URL]）。
 *
 * 鉴权与合规 User-Agent 由 `core/network` 拦截器链注入；每个方法接受一个
 * [SourceAuth] 的 `@Tag` 参数（调用方传 `SourceAuths.bangumi`），
 * 由 [com.acgcompass.core.network.interceptor.AuthInterceptor] 据此读取 Bangumi 凭据并注入
 * `Authorization: Bearer <token>`（无凭据时透传，公共数据匿名可读）。
 *
 * 所有方法返回 [Response]，以便 [BangumiRemoteDataSource] 检视 HTTP 状态码并经
 * [com.acgcompass.core.network.HttpErrorMapper] 映射为领域 `AppError`（不依赖异常路径）。
 */
interface BangumiApi {

    /** 条目详情（缓存 300s）。 */
    @GET("v0/subjects/{id}")
    suspend fun getSubject(
        @Path("id") subjectId: Int,
        @Tag auth: SourceAuth,
        @retrofit2.http.Header("Cache-Control") cacheControl: String? = null,
    ): Response<BangumiSubjectDto>

    /** 搜索条目（实验性 API）：body 携带 keyword/sort/filter，limit/offset 走 query。 */
    @POST("v0/search/subjects")
    suspend fun searchSubjects(
        @Body request: BangumiSearchRequestDto,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Tag auth: SourceAuth,
    ): Response<BangumiPagedSubjectDto>

    /** 条目关联人物。 */
    @GET("v0/subjects/{id}/persons")
    suspend fun getSubjectPersons(
        @Path("id") subjectId: Int,
        @Tag auth: SourceAuth,
    ): Response<List<BangumiRelatedPersonDto>>

    /** 条目关联角色。 */
    @GET("v0/subjects/{id}/characters")
    suspend fun getSubjectCharacters(
        @Path("id") subjectId: Int,
        @Tag auth: SourceAuth,
    ): Response<List<BangumiRelatedCharacterDto>>

    /** 条目关联作品。 */
    @GET("v0/subjects/{id}/subjects")
    suspend fun getSubjectRelations(
        @Path("id") subjectId: Int,
        @Tag auth: SourceAuth,
    ): Response<List<BangumiSubjectRelationDto>>

    /** 用户收藏列表（subject_type / type 为收藏类型，limit/offset 分页）。 */
    @GET("v0/users/{username}/collections")
    suspend fun getUserCollections(
        @Path("username") username: String,
        @Query("subject_type") subjectType: Int? = null,
        @Query("type") type: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Tag auth: SourceAuth,
    ): Response<BangumiPagedUserCollectionDto>

    /** 用户对单个条目的收藏（个人评分 / 短评 / 进度 / 状态）。 */
    @GET("v0/users/{username}/collections/{subject_id}")
    suspend fun getUserSubjectCollection(
        @Path("username") username: String,
        @Path("subject_id") subjectId: Int,
        @Tag auth: SourceAuth,
    ): Response<BangumiUserSubjectCollectionDto>

    /** 当前登录用户（需 Bearer 授权）。 */
    @GET("v0/me")
    suspend fun getMe(@Tag auth: SourceAuth): Response<BangumiMeDto>

    /**
     * 修改当前用户对某条目的**已存在**收藏（需 Bearer）。Bangumi v0 用 PATCH 修改已收藏条目；
     * 若条目尚未收藏会返回 404，调用方据此回退到 [createUserCollection]（POST 新建）。成功返回 204。
     */
    @Headers("Content-Type: application/json")
    @PATCH("v0/users/-/collections/{subject_id}")
    suspend fun patchUserCollection(
        @Path("subject_id") subjectId: Int,
        @Body body: BangumiUpdateCollectionRequest,
        @Tag auth: SourceAuth,
    ): Response<Unit>

    /**
     * 新建当前用户对某条目的收藏（需 Bearer）。未收藏时使用；新建必须带 `type`。成功返回 202。
     */
    @Headers("Content-Type: application/json")
    @POST("v0/users/-/collections/{subject_id}")
    suspend fun createUserCollection(
        @Path("subject_id") subjectId: Int,
        @Body body: BangumiUpdateCollectionRequest,
        @Tag auth: SourceAuth,
    ): Response<Unit>

    /** 每日放送（legacy 公共端点，免鉴权）→ 本季 / 近期在播公共发现池。 */
    @GET("calendar")
    suspend fun getCalendar(@Tag auth: SourceAuth): Response<List<BangumiCalendarDayDto>>

    /**
     * 条目章节列表（`GET /v0/episodes`）。`type=0` 仅本篇。用于动画进度上传时把「看到第 N 集」
     * 映射为前 N 个本篇章节 id。公共数据匿名可读。
     */
    @GET("v0/episodes")
    suspend fun getEpisodes(
        @Query("subject_id") subjectId: Int,
        @Query("type") type: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
        @Tag auth: SourceAuth,
    ): Response<BangumiPagedEpisodeDto>

    /**
     * 批量更新某条目的章节收藏状态（`PATCH /v0/users/-/collections/{subject_id}/episodes`，需 Bearer）。
     * body：`episode_id` 数组 + `type`（EpisodeCollectionType，2=看过）。服务端会重新计算条目完成度。
     * 这是动画「看到第 N 集」的正确上传方式（collection PATCH 的 ep_status 仅书籍可用）。成功返回 204。
     */
    @Headers("Content-Type: application/json")
    @PATCH("v0/users/-/collections/{subject_id}/episodes")
    suspend fun patchEpisodeCollection(
        @Path("subject_id") subjectId: Int,
        @Body body: BangumiPatchEpisodesRequest,
        @Tag auth: SourceAuth,
    ): Response<Unit>
}
