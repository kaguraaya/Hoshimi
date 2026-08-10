package com.acgcompass.data.remote.bangumi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Bangumi 新版私有 API（`next.bgm.tv/p1`）的**公共**接口子集。
 *
 * 当前仅用于「条目短评」——官方 JSON 接口（非网页抓取，符合 「不抓网页」），匿名可读。
 * 不携带鉴权 `@Tag`（公共数据无需 Token），合规 User-Agent 仍由拦截器链注入。
 */
interface BangumiNextApi {

    /**
     * 条目短评（`GET p1/subjects/{id}/comments`）。返回最近短评（含昵称 / 评分 / 短评 / 更新时间）。
     * 用于详情「评论摘要」展示真实他人短评，并作为无剧透雷达的 `publicReviews` 输入。
     */
    @GET("subjects/{id}/comments")
    suspend fun getSubjectComments(
        @Path("id") subjectId: Int,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null,
    ): Response<BangumiNextCommentsResponse>
}

/** 短评列表响应（`data` 为短评数组）。 */
@Serializable
data class BangumiNextCommentsResponse(
    @SerialName("data") val data: List<BangumiNextCommentDto> = emptyList(),
)

/** 单条短评。仅取展示所需字段，其余忽略（`ignoreUnknownKeys`）。 */
@Serializable
data class BangumiNextCommentDto(
    @SerialName("user") val user: BangumiNextUserDto? = null,
    @SerialName("rate") val rate: Int = 0,
    @SerialName("comment") val comment: String = "",
    @SerialName("updatedAt") val updatedAt: Long = 0,
)

/** 短评作者（仅昵称用于展示）。 */
@Serializable
data class BangumiNextUserDto(
    @SerialName("nickname") val nickname: String = "",
)

/**
 * 领域中转：一条可展示的短评（昵称 + 评分 + 文本）。评分 ≤ 0 视为无评分。
 */
data class BangumiComment(
    val nickname: String,
    val rate: Int,
    val text: String,
)
