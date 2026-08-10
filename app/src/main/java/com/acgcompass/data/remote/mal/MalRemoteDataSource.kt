package com.acgcompass.data.remote.mal

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.network.HttpErrorMapper
import com.acgcompass.core.network.interceptor.SourceAuth
import com.acgcompass.core.network.interceptor.SourceAuths
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

/**
 * MyAnimeList 官方 API 远程数据源。
 *
 * 职责：调用 [MalApi] → 检视 HTTP 状态 → 经 [HttpErrorMapper] 映射错误 → DTO 转领域模型，
 * 统一返回 [AppResult]（异常兜底，绝不崩溃）。
 *
 * **启用门控**：仅当用户在本机 [CredentialStore] 显式配置了 Client ID 才启用
 * （[isEnabled]）。未配置时各方法直接以 [AppError.Unauthorized] 短路、**不发请求**；降级编排器
 * 侧由取数器据 [isEnabled] 返回 `null` 来「跳过」本源。
 *
 * 鉴权：所有请求打上 [SourceAuths.mal]（`MalAuth`）标签，由 `AuthInterceptor` 注入——
 * 有用户 OAuth token → `Authorization: Bearer <token>`（可读 `@me` 私有列表）；
 * 仅有 Client ID → 退化为 `X-MAL-CLIENT-ID` 头（公开数据，`client_auth`）。本类不直接读取 token，
 * 只读取 Client ID 用于启用判定（凭据注入由拦截器统一负责）。
 */
@Singleton
class MalRemoteDataSource @Inject constructor(
    private val api: MalApi,
    private val credentialStore: CredentialStore,
) {

    private val auth: SourceAuth = SourceAuths.mal

    /**
     * 是否已由用户显式配置 Client ID。未配置时本源不参与降级、不发请求。
     * 任何读取异常都安全降级为「未启用」（不崩溃）。
     */
    suspend fun isEnabled(): Boolean = runCatching {
        credentialStore.get(CredentialSourceId.MAL)?.clientId?.isNotBlank() == true
    }.getOrDefault(false)

    /** 搜索动画 → 候选 [WorkMatch] 列表（精确置信度由上层匹配器另行计算）。 */
    suspend fun searchAnime(keyword: String, limit: Int? = null, offset: Int? = null): AppResult<List<WorkMatch>> =
        guarded {
            api.searchAnime(query = keyword, limit = limit, offset = offset, auth = auth)
                .bodyOrThrow().data
                .mapNotNull { it.toWorkMatchOrNull() }
        }

    /** 动画详情 → 规范化 [Work]；`id<=0` 视为缺失（`Failure(FieldMissing)`）。 */
    suspend fun getAnime(id: Int): AppResult<Work> = guarded {
        val anime = api.getAnime(animeId = id, auth = auth).bodyOrThrow()
        if (anime.id <= 0) throw AppError.FieldMissing().asException()
        anime.toWork()
    }

    /** 动画 MAL 评分 → 单源 [RatingEntry]；评分缺失返回 `Failure(FieldMissing)`（UI 显示「暂无数据」）。 */
    suspend fun getAnimeRating(id: Int): AppResult<RatingEntry> = guarded {
        api.getAnime(animeId = id, auth = auth).bodyOrThrow().toRatingEntry()
            ?: throw AppError.FieldMissing().asException()
    }

    /**
     * 用户官方动画列表（进度 / 评分）→ 作品 [WorkMatch] 列表。
     *
     * @param userName 用户名或 `@me`（`@me` 需用户 OAuth token；仅有 Client ID 时无法读取私有列表）。
     * @param status 可选状态过滤（watching/completed/on_hold/dropped/plan_to_watch）。
     */
    suspend fun getUserAnimeList(
        userName: String = USER_ME,
        status: String? = null,
        sort: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): AppResult<List<WorkMatch>> = guarded {
        api.getUserAnimeList(
            userName = userName,
            status = status,
            sort = sort,
            limit = limit,
            offset = offset,
            auth = auth,
        ).bodyOrThrow().data
            .mapNotNull { it.toWorkMatchOrNull() }
    }

    /**
     * 启用门控包装：未配置 Client ID 时直接 [AppResult.Failure] 短路（不发请求）；
     * 已启用则执行 [block] 并经 [runCatchingApp] 兜底异常为领域错误（绝不崩溃）。
     */
    private suspend inline fun <T> guarded(crossinline block: suspend () -> T): AppResult<T> {
        if (!isEnabled()) {
            return AppResult.Failure(AppError.Unauthorized(cause = "未配置 MyAnimeList Client ID"))
        }
        return runCatchingApp { block() }
    }

    /**
     * 解析 [Response]：非 2xx 经 [HttpErrorMapper.mapStatusCode] 抛出对应 [AppError]
     * （MAL：`401` token 失效、`403` DoS、`404` 未找到、`429` 限流、`5xx` 服务端错误）；
     * 2xx 但响应体为空抛出 [AppError.FieldMissing]。抛出的领域错误会被外层 [runCatchingApp]
     * 还原为 `AppResult.Failure`。
     */
    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw HttpErrorMapper.mapStatusCode(code()).asException()
        return body() ?: throw AppError.FieldMissing().asException()
    }

    private companion object {
        /** 官方「当前用户」别名（需用户 OAuth token）。 */
        const val USER_ME: String = "@me"
    }
}
