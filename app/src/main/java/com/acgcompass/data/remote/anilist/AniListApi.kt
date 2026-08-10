package com.acgcompass.data.remote.anilist

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.asException
import com.acgcompass.core.network.HttpErrorMapper
import com.acgcompass.core.network.NetworkJson
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.core.network.graphql.GraphQlClient
import com.acgcompass.core.network.graphql.GraphQlResponse
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * AniList GraphQL 接口适配器。
 *
 * 在共享 [GraphQlClient]（薄 OkHttp POST 调用器，公共查询免鉴权，见 `NetworkModule`）之上，
 * 负责：拼装查询 + 变量 → 执行 → 把 **HTTP 状态码 / GraphQL `errors` / 空 `data`** 统一映射为
 * 领域 [AppError]（以异常形式抛出，由上层 `runCatchingApp` 还原为 `AppResult.Failure`）→
 * 反序列化 `data` 为指定 DTO。
 *
 * 不直接返回领域模型；领域转换由 [AniListRemoteDataSource] 调用 [AniListMappers] 完成。
 * 由 `di/AniListModule` 以 [GraphQlClient] + [Json] 构造提供（单例）。
 */
class AniListApi(
    private val client: GraphQlClient,
    private val json: Json = NetworkJson.instance,
) {

    /** 按 ID 取 Media；命中返回 [AniListMediaDto]，条目不存在 / 缺失抛 [AppError]。 */
    suspend fun getMediaById(id: Int): AniListMediaDto {
        val variables = buildJsonObject { put("id", id) }
        val data = queryData(AniListQueries.MEDIA_BY_ID, variables, AniListMediaResponse.serializer())
        return data.media ?: throw AppError.FieldMissing().asException()
    }

    /** 按标题搜索 Media；返回命中列表（可能为空，由上层判定「暂无数据」）。 */
    suspend fun searchMedia(
        keyword: String,
        type: String? = null,
        perPage: Int = AniListQueries.DEFAULT_SEARCH_PER_PAGE,
    ): List<AniListMediaDto> {
        val variables = buildJsonObject {
            put("search", keyword)
            if (type != null) put("type", type)
            put("perPage", perPage)
        }
        val data = queryData(AniListQueries.SEARCH_BY_TITLE, variables, AniListPageResponse.serializer())
        return data.page?.media.orEmpty()
    }

    /** 取当前趋势榜单（公共数据，免鉴权）；返回 Media 列表（可能为空）。 */
    suspend fun getTrending(
        page: Int = 1,
        perPage: Int = AniListQueries.DEFAULT_TRENDING_PER_PAGE,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): List<AniListMediaDto> {
        val variables = buildJsonObject {
            put("page", page.coerceAtLeast(1))
            put("perPage", perPage)
        }
        val data = queryData(AniListQueries.TRENDING_NOW, variables, AniListPageResponse.serializer(), priority)
        return data.page?.media.orEmpty()
    }

    suspend fun getAiringSchedules(
        fromEpochSeconds: Long,
        toEpochSeconds: Long?,
        page: Int = 1,
        perPage: Int = 50,
        priority: RequestPriority = RequestPriority.BACKGROUND,
    ): AniListSchedulePageDto {
        val variables = buildJsonObject {
            put("from", fromEpochSeconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            if (toEpochSeconds != null) {
                put("to", toEpochSeconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            }
            put("page", page.coerceAtLeast(MIN_PAGE))
            put("perPage", perPage.coerceIn(MIN_PAGE, MAX_PAGE_SIZE))
        }
        return queryData(
            AniListQueries.AIRING_SCHEDULES,
            variables,
            AniListScheduleResponse.serializer(),
            priority,
        ).page ?: AniListSchedulePageDto()
    }

    private companion object {
        const val MIN_PAGE = 1
        const val MAX_PAGE_SIZE = 50
    }

    /**
     * 执行查询并把响应解析为 [T]。失败路径统一抛出领域 [AppError]（[asException]）：
     * - HTTP 非 2xx → [HttpErrorMapper.mapStatusCode]（401/403/404/429/5xx…）。
     * - HTTP 2xx 但含 GraphQL `errors[]` → 取首个 error 的 `status` 映射（无 status 兜底 [AppError.Server]）。
     * - `data` 为空 → [AppError.FieldMissing]（UI「暂无数据」）。
     */
    private fun <T> queryDataExecute(response: GraphQlResponse, deserializer: DeserializationStrategy<T>): T {
        if (response.statusCode !in 200..299) {
            throw HttpErrorMapper.mapStatusCode(response.statusCode).asException()
        }
        response.errors?.let { errors ->
            if (errors is JsonArray && errors.isNotEmpty()) {
                throw mapGraphQlErrors(errors).asException()
            }
        }
        val dataElement = response.data as? JsonObject
            ?: throw AppError.FieldMissing().asException()
        return json.decodeFromJsonElement(deserializer, dataElement)
    }

    private suspend fun <T> queryData(
        query: String,
        variables: JsonObject,
        deserializer: DeserializationStrategy<T>,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): T = queryDataExecute(client.query(query, variables, priority), deserializer)

    /** 把 GraphQL `errors[]` 映射为领域错误：优先按首个 error 的 `status` 映射，否则兜底 [AppError.Server]。 */
    private fun mapGraphQlErrors(errors: JsonArray): AppError {
        val status = errors.asSequence()
            .mapNotNull { runCatching { json.decodeFromJsonElement(AniListGraphQlError.serializer(), it) }.getOrNull() }
            .firstNotNullOfOrNull { it.status }
        return if (status != null) HttpErrorMapper.mapStatusCode(status) else AppError.Server()
    }
}
