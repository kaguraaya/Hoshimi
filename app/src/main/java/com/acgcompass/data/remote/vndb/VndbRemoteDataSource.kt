package com.acgcompass.data.remote.vndb

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.network.HttpErrorMapper
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.core.network.interceptor.SourceAuth
import com.acgcompass.core.network.interceptor.SourceAuths
import com.acgcompass.core.network.interceptor.withPriority
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.visualnovel.VisualNovelDetail
import com.acgcompass.domain.visualnovel.VisualNovelListItem
import com.acgcompass.domain.visualnovel.VisualNovelPage
import com.acgcompass.domain.visualnovel.VisualNovelSort
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response

/**
 * VNDB 远程数据源。
 *
 * 职责：构造 [VndbQueryRequestDto]（`filters` + `fields`）→ 调用 [VndbApi] → 检视 HTTP 状态 →
 * 经 [HttpErrorMapper] 映射错误 → DTO 转领域模型 → **按成人内容开关过滤** → 统一返回 [AppResult]
 * （异常兜底，绝不崩溃）。
 *
 * 鉴权：VNDB 公共查询免鉴权；所有请求打上 [SourceAuths.vndb]（`TokenHeaderAuth(VNDB,"Token")`）标签，
 * 由拦截器在用户本机配置了可选 token 时注入 `Authorization: Token <token>`，否则透传匿名查询。
 *
 * 成人内容分级：各查询方法接受可选 [showAdultContent] 布尔参数；为 `null` 时
 * 从 [SettingsDataStore.showAdultContent] 读取当前开关。`false` 时过滤/隐藏被标记为成人内容
 * （`image.sexual >= [VNDB_ADULT_SEXUAL_THRESHOLD]`）的条目（[VndbVnDto.isAdult]）。
 */
@Singleton
class VndbRemoteDataSource @Inject constructor(
    private val api: VndbApi,
    private val settingsDataStore: SettingsDataStore,
) {

    private val auth: SourceAuth = SourceAuths.vndb
    private val detailMutex = Mutex()
    private val detailMemo = LinkedHashMap<String, MemoizedDetail>()

    private class MemoizedDetail(val dto: VndbVnDto, val fetchedAt: Long)

    /**
     * 按关键字搜索 VN → 候选 [WorkMatch] 列表（精确置信度由上层匹配器另行计算）。
     * 成人内容按 [showAdultContent]（缺省读取设置）过滤。
     */
    suspend fun searchVn(
        keyword: String,
        showAdultContent: Boolean? = null,
        results: Int? = null,
        page: Int? = null,
    ): AppResult<List<WorkMatch>> = runCatchingApp {
        val allowAdult = resolveAdultFlag(showAdultContent)
        val request = VndbQueryRequestDto(
            filters = VndbFilters.bySearch(keyword),
            fields = VndbFieldSets.VN_COMPACT,
            sort = VndbSort.SEARCH_RANK,
            results = results,
            page = page,
        )
        api.queryVn(request, auth).bodyOrThrow().results
            .filter { allowAdult || !it.isAdult() }
            .map { it.toWorkMatch() }
    }

    suspend fun searchPage(
        keyword: String,
        page: Int,
        pageSize: Int,
        showAdultContent: Boolean? = null,
    ): AppResult<VisualNovelPage> = runCatchingApp {
        val allowAdult = resolveAdultFlag(showAdultContent)
        val response = api.queryVn(
            VndbQueryRequestDto(
                filters = VndbFilters.bySearch(keyword),
                fields = VndbFieldSets.VN_COMPACT,
                sort = VndbSort.SEARCH_RANK,
                results = pageSize.coerceIn(1, 100),
                page = page.coerceAtLeast(1),
            ),
            auth,
        ).bodyOrThrow()
        response.toVisualNovelPage(page, allowAdult)
    }

    /** Two independent quality routes: Bayesian rating and vote-count popularity. */
    suspend fun getAutomaticPool(
        page: Int,
        pageSize: Int,
        showAdultContent: Boolean? = null,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<VisualNovelPage> = runCatchingApp {
        val allowAdult = resolveAdultFlag(showAdultContent)
        val perRoute = pageSize.coerceIn(2, 100).let { (it + 1) / 2 }
        val rating = api.queryVn(
            VndbQueryRequestDto(
                filters = VndbFilters.minimumVotes(MINIMUM_AUTOMATIC_VOTES),
                fields = VndbFieldSets.VN_COMPACT,
                sort = VndbSort.RATING,
                reverse = true,
                results = perRoute,
                page = page.coerceAtLeast(1),
            ),
            auth.withPriority(priority),
        ).bodyOrThrow()
        val popular = api.queryVn(
            VndbQueryRequestDto(
                filters = VndbFilters.minimumVotes(MINIMUM_AUTOMATIC_VOTES),
                fields = VndbFieldSets.VN_COMPACT,
                sort = VndbSort.VOTE_COUNT,
                reverse = true,
                results = perRoute,
                page = page.coerceAtLeast(1),
            ),
            auth.withPriority(priority),
        ).bodyOrThrow()
        val merged = (rating.results + popular.results)
            .asSequence()
            .distinctBy { it.id }
            .filter { allowAdult || !it.isAdult() }
            .sortedWith(compareByDescending<VndbVnDto> { it.rating ?: 0f }.thenByDescending { it.voteCount ?: 0 })
            .take(pageSize)
            .toList()
        VisualNovelPage(
            items = merged.map { it.toListItem() },
            page = page.coerceAtLeast(1),
            hasMore = rating.more || popular.more,
        )
    }

    suspend fun getRanking(request: VndbRankingRequest): AppResult<VisualNovelPage> {
        val sort = request.sort
        val page = request.page
        val pageSize = request.pageSize
        val randomSeed = request.randomSeed
        val showAdultContent = request.showAdultContent
        val priority = request.priority
        if (sort == VisualNovelSort.RANDOM) {
            return when (
                val pool = getAutomaticPool(page, pageSize, showAdultContent, priority)
            ) {
                is AppResult.Success -> AppResult.Success(
                    pool.data.copy(
                        items = pool.data.items.shuffled(Random(randomSeed xor page.toLong())),
                    ),
                )
                is AppResult.Failure -> pool
            }
        }
        return runCatchingApp {
            val allowAdult = resolveAdultFlag(showAdultContent)
            val response = api.queryVn(
                VndbQueryRequestDto(
                    filters = VndbFilters.minimumVotes(MINIMUM_AUTOMATIC_VOTES),
                    fields = VndbFieldSets.VN_COMPACT,
                    sort = when (sort) {
                        VisualNovelSort.RATING -> VndbSort.RATING
                        VisualNovelSort.POPULARITY -> VndbSort.VOTE_COUNT
                        VisualNovelSort.RELEASE_DATE -> VndbSort.RELEASED
                        VisualNovelSort.RANDOM -> error("Random ranking is handled before this request")
                    },
                    reverse = true,
                    results = pageSize.coerceIn(1, 100),
                    page = page.coerceAtLeast(1),
                ),
                auth.withPriority(priority),
            ).bodyOrThrow()
            response.toVisualNovelPage(page, allowAdult)
        }
    }

    suspend fun getByDeveloper(
        producerId: String,
        page: Int,
        pageSize: Int,
        showAdultContent: Boolean? = null,
    ): AppResult<VisualNovelPage> = runCatchingApp {
        val allowAdult = resolveAdultFlag(showAdultContent)
        val response = api.queryVn(
            VndbQueryRequestDto(
                filters = VndbFilters.byDeveloper(producerId),
                fields = VndbFieldSets.VN_COMPACT,
                sort = VndbSort.RATING,
                reverse = true,
                results = pageSize.coerceIn(1, 100),
                page = page.coerceAtLeast(1),
            ),
            auth,
        ).bodyOrThrow()
        response.toVisualNovelPage(page, allowAdult)
    }

    suspend fun getVnDetail(id: String, showAdultContent: Boolean? = null): AppResult<VisualNovelDetail> =
        runCatchingApp {
            val allowAdult = resolveAdultFlag(showAdultContent)
            val vn = fetchVnById(id)
            if (!allowAdult && vn.isAdult()) throw AppError.FieldMissing().asException()
            vn.toVisualNovelDetail()
        }

    /**
     * 按 vndbid 取 VN 详情 → 规范化 [Work]；`results` 为空视为缺失（`Failure(FieldMissing)`）。
     * 当条目为成人内容且 [showAdultContent] 为 `false` 时，按「隐藏」处理（`Failure(FieldMissing)`）。
     */
    suspend fun getVn(id: String, showAdultContent: Boolean? = null): AppResult<Work> = runCatchingApp {
        val allowAdult = resolveAdultFlag(showAdultContent)
        val vn = fetchVnById(id)
        if (!allowAdult && vn.isAdult()) throw AppError.FieldMissing().asException()
        vn.toWork()
    }

    /**
     * 按 vndbid 取 VN 评分 → 单源 [RatingEntry]（保留 10–100 源标度）；评分缺失返回
     * `Failure(FieldMissing)`（UI 显示「暂无数据」，不以 0 伪造评分）。
     * 成人内容门控同 [getVn]。
     */
    suspend fun getVnRating(id: String, showAdultContent: Boolean? = null): AppResult<RatingEntry> = runCatchingApp {
        val allowAdult = resolveAdultFlag(showAdultContent)
        val vn = fetchVnById(id)
        if (!allowAdult && vn.isAdult()) throw AppError.FieldMissing().asException()
        vn.toRatingEntry() ?: throw AppError.FieldMissing().asException()
    }

    /** 按 id 取单个 VN DTO；`results` 为空抛出 [AppError.FieldMissing]。 */
    /**
     * 按 id 取详情。作品页会从三条路径（作品信息 / 评分 / 视觉小说详情）各要一次同一份数据；
     * VNDB 并发为 1 且每秒 1 次，三次重复请求会让页面白等数秒并多占官方的执行时间配额。
     * 这里串行化并短时复用结果：同一 id 在 [DETAIL_MEMO_TTL_MS] 内只真正请求一次。
     */
    private suspend fun fetchVnById(id: String): VndbVnDto = detailMutex.withLock {
        val now = System.currentTimeMillis()
        detailMemo[id]?.takeIf { now - it.fetchedAt < DETAIL_MEMO_TTL_MS }?.let { return@withLock it.dto }
        val request = VndbQueryRequestDto(
            filters = VndbFilters.byId(id),
            fields = VndbFieldSets.VN_DETAIL,
            results = 1,
        )
        val dto = api.queryVn(request, auth).bodyOrThrow().results.firstOrNull()
            ?: throw AppError.FieldMissing().asException()
        if (detailMemo.size >= DETAIL_MEMO_MAX_ENTRIES) detailMemo.remove(detailMemo.keys.first())
        detailMemo[id] = MemoizedDetail(dto, System.currentTimeMillis())
        dto
    }

    private suspend fun resolveAdultFlag(explicit: Boolean?): Boolean =
        explicit ?: settingsDataStore.showAdultContent.first()

    /**
     * 解析 [Response]：非 2xx 经 [HttpErrorMapper.mapStatusCode] 抛出对应 [AppError]
     * （VNDB：`401` 无效 token、`404` 路径错误、`429` 限流、`5xx` 服务端错误）；2xx 但响应体为空
     * 抛出 [AppError.FieldMissing]。抛出的领域错误会被外层 [runCatchingApp] 还原为
     * `AppResult.Failure`。
     */
    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw HttpErrorMapper.mapStatusCode(code()).asException()
        return body() ?: throw AppError.FieldMissing().asException()
    }

    private fun VndbVnResponseDto.toVisualNovelPage(page: Int, allowAdult: Boolean): VisualNovelPage = VisualNovelPage(
        items = results.filter { allowAdult || !it.isAdult() }.map { it.toListItem() },
        page = page.coerceAtLeast(1),
        hasMore = more,
    )

    private fun VndbVnDto.toListItem(): VisualNovelListItem = VisualNovelListItem(
        work = toWork(),
        rating = rating,
        voteCount = voteCount ?: 0,
        adult = isAdult(),
        previewUrl = image?.thumbnail?.takeIf { it.isNotBlank() } ?: image?.url,
        originalUrl = image?.url,
    )

    private companion object {
        const val MINIMUM_AUTOMATIC_VOTES = 50
        const val DETAIL_MEMO_TTL_MS = 60_000L
        const val DETAIL_MEMO_MAX_ENTRIES = 16
    }
}

data class VndbRankingRequest(
    val sort: VisualNovelSort,
    val page: Int,
    val pageSize: Int,
    val randomSeed: Long = 0L,
    val showAdultContent: Boolean? = null,
    val priority: RequestPriority = RequestPriority.FOREGROUND,
)
