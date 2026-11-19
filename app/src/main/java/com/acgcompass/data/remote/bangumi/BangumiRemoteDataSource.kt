package com.acgcompass.data.remote.bangumi

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.common.withCause
import com.acgcompass.core.network.HttpErrorMapper
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.core.network.interceptor.SourceAuth
import com.acgcompass.core.network.interceptor.SourceAuths
import com.acgcompass.core.network.interceptor.withPriority
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

/**
 * Bangumi 真实排行榜分页结果。[items] 该页（[WorkMatch] + 评分，按 rank 升序）；
 * [total] 为过滤后总条目数（Bangumi 分页响应 `total`），供上层据此判断是否还有下一页，
 * 避免「某页返回数 < limit 即误判到底」。
 */
data class BangumiRankedPage(
    val items: List<Pair<WorkMatch, RatingEntry?>>,
    val total: Int,
)

/** 单次条目响应同时提供详情资料、社区标签和 Bangumi 评分。 */
data class BangumiSubjectBundle(
    val work: Work,
    val rating: RatingEntry?,
    val subject: BangumiSubjectDto,
)

/**
 * Bangumi 远程主数据源。
 *
 * 职责：调用 [BangumiApi] → 检视 HTTP 状态 → 经 [HttpErrorMapper] 映射错误 → DTO 转领域模型，
 * 统一返回 [AppResult]（异常兜底，绝不崩溃）。
 *
 * 鉴权：所有请求都打上 [SourceAuths.bangumi] 标签（见 [SourceAuth] / `networkSource`），
 * 由拦截器在有凭据时注入 `Authorization: Bearer <token>`，无凭据时匿名读取公共数据。
 */
@Singleton
class BangumiRemoteDataSource @Inject constructor(
    private val api: BangumiApi,
    private val nextApi: BangumiNextApi,
    /** 搜索和榜单结果顺带存进本地作品库（基础资料）。 */
    private val subjectSink: BangumiSubjectSink = BangumiSubjectSink {},
) {

    private val auth: SourceAuth = SourceAuths.bangumi
    private val subjectCoalescer = RequestCoalescer<BangumiSubjectDto>(SHARED_RESULT_MILLIS)
    private val personsCoalescer = RequestCoalescer<List<BangumiRelatedPersonDto>>(SHARED_RESULT_MILLIS)
    private val charactersCoalescer = RequestCoalescer<List<BangumiRelatedCharacterDto>>(SHARED_RESULT_MILLIS)
    private val relationsCoalescer = RequestCoalescer<List<BangumiSubjectRelationDto>>(SHARED_RESULT_MILLIS)

    /** 条目详情 → 规范化 [Work]。 */
    suspend fun getSubject(subjectId: Int): AppResult<Work> = runCatchingApp {
        fetchSubjectDto(subjectId, RequestPriority.FOREGROUND).toWork()
    }

    /** 以一次条目请求同时取得规范化作品与评分，避免详情页重复占用配额。 */
    suspend fun getSubjectBundle(
        subjectId: Int,
        priority: RequestPriority = RequestPriority.FOREGROUND,
        fresh: Boolean = false,
    ): AppResult<BangumiSubjectBundle> = runCatchingApp {
        val subject = fetchSubjectDto(subjectId, priority, fresh)
        BangumiSubjectBundle(
            work = subject.toWork(),
            rating = subject.rating.toRatingEntry(),
            subject = subject,
        )
    }

    /**
     * 条目详情**原始 DTO**（含社区标签的标注人数 [BangumiTagDto.count]、评分人数、集数、平台等，
     * `toWork()` 会丢弃这些字段）。构建 work_features 缓存时保留口味画像所需的 `count_xk`。
     */
    suspend fun getSubjectDto(
        subjectId: Int,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<BangumiSubjectDto> = runCatchingApp {
        fetchSubjectDto(subjectId, priority)
    }

    /**
     * 条目最近短评（next.bgm.tv/p1 公共 JSON 接口）。返回非空短评列表（昵称 + 评分 + 文本）。
     * 失败 / 空返回空列表（best-effort，不伪造，绝不崩溃）。
     */
    suspend fun getSubjectComments(subjectId: Int, limit: Int = 20): AppResult<List<BangumiComment>> = runCatchingApp {
        val resp = nextApi.getSubjectComments(subjectId, limit = limit, offset = 0)
        if (!resp.isSuccessful) return@runCatchingApp emptyList()
        resp.body()?.data.orEmpty()
            .mapNotNull { dto ->
                val text = dto.comment.trim()
                if (text.isEmpty()) {
                    null
                } else {
                    BangumiComment(
                        nickname = dto.user?.nickname?.trim().orEmpty().ifEmpty { "匿名用户" },
                        rate = dto.rate,
                        text = text,
                    )
                }
            }
    }

    /** 条目评分 → 单源 [RatingEntry]；评分缺失返回 `Failure(FieldMissing)`（UI 显示「暂无数据」）。 */
    suspend fun getSubjectRating(
        subjectId: Int,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<RatingEntry> = runCatchingApp {
        val subject = fetchSubjectDto(subjectId, priority)
        subject.rating.toRatingEntry() ?: throw AppError.FieldMissing().asException()
    }

    private suspend fun fetchSubjectDto(
        subjectId: Int,
        priority: RequestPriority,
        fresh: Boolean = false,
    ): BangumiSubjectDto {
        // fresh：用户下拉刷新，跳过 HTTP 缓存（条目响应带 max-age）和短时结果复用，直接向服务器取最新的。
        val result = subjectCoalescer.get("$subjectId", priority, fresh) {
            runCatchingApp {
                api.getSubject(subjectId, auth.withPriority(priority), noCacheIf(fresh)).bodyOrThrow()
            }
        }
        return when (result) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> throw result.error.asException()
        }
    }

    /** 搜索条目 → 候选 [WorkMatch] 列表（精确置信度由上层匹配器另行计算）。 */
    @Suppress("LongParameterList")
    suspend fun searchSubjects(
        keyword: String,
        sort: String? = BangumiSearchSort.MATCH,
        filter: BangumiSearchFilterDto? = null,
        limit: Int? = null,
        offset: Int? = null,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<List<WorkMatch>> = runCatchingApp {
        val request = BangumiSearchRequestDto(keyword = keyword, sort = sort, filter = filter)
        val paged = api.searchSubjects(request, limit, offset, auth.withPriority(priority)).bodyOrThrow()
        subjectSink.accept(paged.data)
        paged.data.map { it.toWorkMatch() }
    }

    /**
     * 真实排行榜按 `sort=rank` 浏览动画条目，返回每条 [WorkMatch] 和真实 [RatingEntry]（可空）。
     * 用于发现页「榜单」的准确排序（替代仅按本地缓存评分排序的近似榜单）。
     *
     * @param airDate 可空的开播日期范围过滤（如 `[">=2026-01-01", "<2026-04-01"]` 即本季）；
     *                为 `null` 时为总榜。空 keyword + filter 为 Bangumi 浏览语义。
     */
    suspend fun searchRankedSubjects(
        airDate: List<String>? = null,
        limit: Int = 30,
        offset: Int = 0,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<BangumiRankedPage> = runCatchingApp {
        val filter = BangumiSearchFilterDto(
            type = listOf(BangumiSubjectType.ANIME),
            airDate = airDate,
            // 排除未排名条目（rank=0/缺失），否则它们会排到最前导致「全是低分少评」。
            rank = listOf(">0"),
            nsfw = false,
        )
        val request = BangumiSearchRequestDto(
            keyword = "",
            sort = BangumiSearchSort.RANK,
            filter = filter,
        )
        val (subjects, total) = collectSearchPages(request, limit, offset, auth.withPriority(priority))
        subjectSink.accept(subjects)
        // 按真实 rank 升序（排名越小越靠前）客户端兜底排序，nulls/0 视为最差。
        val items = subjects
            .sortedBy { it.rating?.rank?.takeIf { r -> r > 0 } ?: Int.MAX_VALUE }
            .map { it.toWorkMatch() to it.rating.toRatingEntry() }
        // 携带过滤后总数，供分页判定是否还有更多（不依赖返回数 >= limit）。
        BangumiRankedPage(items = items, total = total)
    }

    /**
     * 连续取满 [limit] 条搜索结果。
     *
     * 搜索接口的单页条数由服务器封顶（实测 2026-11 为 20 条，官方规范未写上限且注明「实验性、随时可能改动」）：
     * 请求 `limit=50` 只会回来 20 条。调用方按 `offset = 页码 × limit` 翻页时，每页都会漏掉后面的条目
     * ——榜单会缺一大半。这里按**实际返回条数**推进 offset，直到取满或到底；服务器以后放宽上限时自动少发请求。
     * 第一页失败整体失败；后续页失败则返回已取到的部分。
     */
    private suspend fun collectSearchPages(
        request: BangumiSearchRequestDto,
        limit: Int,
        offset: Int,
        requestAuth: SourceAuth,
    ): Pair<List<BangumiSubjectDto>, Int> {
        val first = api.searchSubjects(request, limit, offset, requestAuth).bodyOrThrow()
        val collected = first.data.toMutableList()
        var lastPageSize = first.data.size
        while (lastPageSize > 0 && collected.size < limit && offset + collected.size < first.total) {
            val next = runCatching {
                api.searchSubjects(request, limit - collected.size, offset + collected.size, requestAuth).bodyOrThrow()
            }.getOrNull()
            lastPageSize = next?.data?.size ?: 0
            next?.data?.let(collected::addAll)
        }
        return collected.take(limit) to first.total
    }

    /** 条目关联人物。 */
    suspend fun getSubjectPersons(
        subjectId: Int,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<List<BangumiRelatedPersonDto>> = personsCoalescer.get("$subjectId", priority) {
        runCatchingApp { api.getSubjectPersons(subjectId, auth.withPriority(priority)).bodyOrThrow() }
    }

    /** 条目关联角色。 */
    suspend fun getSubjectCharacters(
        subjectId: Int,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<List<BangumiRelatedCharacterDto>> = charactersCoalescer.get("$subjectId", priority) {
        runCatchingApp { api.getSubjectCharacters(subjectId, auth.withPriority(priority)).bodyOrThrow() }
    }

    /** 条目关联作品。 */
    suspend fun getSubjectRelations(subjectId: Int): AppResult<List<BangumiSubjectRelationDto>> =
        relationsCoalescer.get("$subjectId", RequestPriority.FOREGROUND) {
            runCatchingApp { api.getSubjectRelations(subjectId, auth).bodyOrThrow() }
        }

    /** 用户收藏列表（个人评分 / 短评 / 进度 / 状态）。 */
    suspend fun getUserCollections(
        username: String,
        subjectType: Int? = null,
        type: Int? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): AppResult<BangumiPagedUserCollectionDto> = runCatchingApp {
        api.getUserCollections(username, subjectType, type, limit, offset, auth).bodyOrThrow()
    }

    /** 用户对单个条目的收藏。 */
    suspend fun getUserSubjectCollection(
        username: String,
        subjectId: Int,
    ): AppResult<BangumiUserSubjectCollectionDto> = runCatchingApp {
        api.getUserSubjectCollection(username, subjectId, auth).bodyOrThrow()
    }

    /** 当前登录用户（需 Bearer 授权）。 */
    suspend fun getMe(): AppResult<BangumiMeDto> = runCatchingApp {
        api.getMe(auth).bodyOrThrow()
    }

    /**
     * 新增 / 修改当前用户对某条目的收藏（状态 / 评分 / 进度 / 短评 / 标签）。需 Bearer 授权。
     * 仅提交非空字段（局部更新）。成功（2xx，含 202/204）返回 Unit；失败经 [HttpErrorMapper] 映射。
     */
    suspend fun updateUserCollection(
        subjectId: Int,
        type: Int? = null,
        rate: Int? = null,
        epStatus: Int? = null,
        volStatus: Int? = null,
        comment: String? = null,
        tags: List<String>? = null,
        private: Boolean? = null,
    ): AppResult<Unit> = runCatchingApp {
        val body = BangumiUpdateCollectionRequest(
            type = type,
            rate = rate,
            // Bangumi 收藏修改端点对**非书籍**不接受 ep_status/vol_status（会 400
            // "can't set 'vol_status' or 'ep_status' on non-book subject"）。动画进度由专用章节端点处理，
            // 此处不提交，避免整条保存失败。仅书籍场景才带卷进度（当前 App 以动画为主，统一不带）。
            epStatus = null,
            volStatus = null,
            comment = comment,
            tags = tags,
            // 可见性为 null 时按 PATCH 语义保留服务端原值。
            private = private,
        )
        // Bangumi v0 用 PATCH 修改**已存在**收藏；条目尚未收藏会 404，此时回退 POST 新建。
        var method = "PATCH"
        var response = api.patchUserCollection(subjectId, body, auth)
        if (response.code() == 404) {
            method = "POST"
            response = api.createUserCollection(subjectId, body, auth)
        }
        if (!response.isSuccessful) {
            // 暴露真实失败详情（HTTP 码 + 服务端错误体）便于定位，而非只给「请稍后重试」。
            val code = response.code()
            val errBody = runCatching { response.errorBody()?.string() }.getOrNull()
                ?.replace(Regex("\\s+"), " ")?.trim()?.take(300)
            val detail = "Bangumi 收藏写入失败 HTTP $code（$method /v0/users/-/collections/$subjectId）" +
                (if (!errBody.isNullOrBlank()) "：$errBody" else "")
            throw HttpErrorMapper.mapStatusCode(code).withCause(detail).asException()
        }
    }

    /**
     * 每日放送（legacy 公共端点）→ 每条 [Work] + 可空 [RatingEntry]（评分缺失为 null，不伪造）。
     * 接口带 `max-age=3600`，[fresh] 为 true（用户下拉刷新）时跳过 HTTP 缓存直接向服务器取。
     */
    suspend fun getCalendar(
        priority: RequestPriority = RequestPriority.FOREGROUND,
        fresh: Boolean = false,
    ): AppResult<List<Pair<WorkMatch, RatingEntry?>>> = runCatchingApp {
        val days = api.getCalendar(auth.withPriority(priority), noCacheIf(fresh)).bodyOrThrow()
        days.flatMap { it.items }
            .distinctBy { it.id }
            .filter { it.id > 0 }
            .map { it.toWorkMatch() to it.toRatingEntry() }
    }

    suspend fun getCalendarDays(
        priority: RequestPriority = RequestPriority.BACKGROUND,
        fresh: Boolean = false,
    ): AppResult<List<BangumiCalendarDayDto>> = runCatchingApp {
        api.getCalendar(auth.withPriority(priority), noCacheIf(fresh)).bodyOrThrow()
    }

    private fun noCacheIf(fresh: Boolean): String? = if (fresh) "no-cache" else null

    /**
     * 取条目「本篇」章节 id（按集数升序）。用于把「看到第 N 集」映射为前 N 个本篇章节 id。
     * 公共数据匿名可读；失败 / 空返回空列表（不伪造）。
     */
    suspend fun getMainEpisodeIds(subjectId: Int): AppResult<List<Int>> = runCatchingApp {
        val paged = api.getEpisodes(
            subjectId = subjectId,
            type = BangumiEpisodeType.MAIN,
            limit = EPISODE_PAGE_LIMIT,
            offset = 0,
            auth = auth,
        ).bodyOrThrow()
        paged.data
            .sortedBy { it.ep ?: it.sort }
            .map { it.id }
    }

    /**
     * 把某条目的前 [watchedCount] 个本篇章节标记为「看过」（需 Bearer）。
     * 取本篇章节 id → `PATCH /v0/users/-/collections/{id}/episodes`（type=2）。
     * [watchedCount] ≤ 0 时不操作。成功返回 Unit；失败经 [HttpErrorMapper] 映射并带详情。
     */
    suspend fun markEpisodesWatched(subjectId: Int, watchedCount: Int): AppResult<Unit> = runCatchingApp {
        if (watchedCount <= 0) return@runCatchingApp
        val ids = api.getEpisodes(
            subjectId = subjectId,
            type = BangumiEpisodeType.MAIN,
            limit = EPISODE_PAGE_LIMIT,
            offset = 0,
            auth = auth,
        ).bodyOrThrow().data
            .sortedBy { it.ep ?: it.sort }
            .map { it.id }
            .take(watchedCount)
        if (ids.isEmpty()) return@runCatchingApp
        val response = api.patchEpisodeCollection(
            subjectId = subjectId,
            body = BangumiPatchEpisodesRequest(
                episodeId = ids,
                type = BangumiEpisodeCollectionType.DONE,
            ),
            auth = auth,
        )
        if (!response.isSuccessful) {
            val code = response.code()
            val errBody = runCatching { response.errorBody()?.string() }.getOrNull()
                ?.replace(Regex("\\s+"), " ")?.trim()?.take(200)
            val detail = "Bangumi 章节进度写入失败 HTTP $code" +
                (if (!errBody.isNullOrBlank()) "：$errBody" else "")
            throw HttpErrorMapper.mapStatusCode(code).withCause(detail).asException()
        }
    }

    /** 判断条目在 Bangumi 是否存在（用于写回前确认，避免对无词条作品反复报错）。 */
    suspend fun subjectExists(subjectId: Int): Boolean = api.getSubject(subjectId, auth).isSuccessful

    /**
     * 2xx 但响应体为空（缺字段 / 空结果）抛出 [AppError.FieldMissing]。抛出的领域错误会被
     * 外层 [runCatchingApp] 捕获并原样还原为 `AppResult.Failure`。
     */
    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw HttpErrorMapper.mapStatusCode(code()).asException()
        return body() ?: throw AppError.FieldMissing().asException()
    }

    private companion object {
        /** 同一条目的条目信息、人物、角色、关联作品，一分钟内复用上一次的结果。 */
        const val SHARED_RESULT_MILLIS = 60_000L

        /** `GET /v0/episodes` 官方规范的单页上限（取满一页，长篇不会被截断）。 */
        const val EPISODE_PAGE_LIMIT = 200
    }
}
