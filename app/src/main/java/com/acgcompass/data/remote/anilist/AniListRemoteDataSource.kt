package com.acgcompass.data.remote.anilist

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.schedule.BroadcastScheduleEntry
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AniList 远程补充数据源。
 *
 * 职责：调用 [AniListApi]（GraphQL）→ DTO 经 [AniListMappers] 转领域模型，统一返回 [AppResult]
 * （异常兜底，绝不崩溃）。
 *
 * 鉴权：[AniListApi] 复用的 [com.acgcompass.core.network.graphql.GraphQlClient] 已打上
 * `SourceAuths.anilist` 标签；有凭据时由拦截器注入 `Authorization: Bearer <token>`，无凭据时
 * 透传匿名读取公共数据（**AniList 公共查询免鉴权**）。
 *
 * 降级中文兜底：AniList 无中文标题，[Work.titles.canonical] 取英文标题，
 * 当 Bangumi 不可用时作为回退展示来源。
 */
@Singleton
class AniListRemoteDataSource @Inject constructor(
    private val api: AniListApi,
) {

    /** 按 AniList Media ID 取详情 → 规范化 [Work]。 */
    suspend fun getMedia(mediaId: Int): AppResult<Work> = runCatchingApp {
        api.getMediaById(mediaId).toWork()
    }

    /** 按 Media ID 取评分 → 单源 [RatingEntry]；评分缺失返回 `Failure(FieldMissing)`（UI「暂无数据」）。 */
    suspend fun getMediaRating(
        mediaId: Int,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<RatingEntry> = runCatchingApp {
        api.getMediaById(mediaId, priority).toRatingEntry() ?: throw AppError.FieldMissing().asException()
    }

    /**
     * 按标题搜索 → 候选 [WorkMatch] 列表（精确置信度由上层匹配器另行计算）。
     *
     * @param type 可选媒介过滤（`ANIME` / `MANGA`，见 [AniListMediaType]）；为 `null` 时不限类型。
     */
    suspend fun searchMedia(
        keyword: String,
        type: String? = null,
        perPage: Int = AniListQueries.DEFAULT_SEARCH_PER_PAGE,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<List<WorkMatch>> = runCatchingApp {
        api.searchMedia(keyword, type, perPage, priority).map { it.toWorkMatch() }
    }

    /**
     * 取当前趋势榜单（公共数据，免鉴权）。返回每条 Media 的 [Work] 与可空 [RatingEntry]
     * （评分缺失为 `null` → 不伪造，由上层按需写入）。供发现页 / 首页公共发现池使用。
     */
    suspend fun getTrending(
        page: Int = 1,
        perPage: Int = AniListQueries.DEFAULT_TRENDING_PER_PAGE,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<List<Pair<WorkMatch, RatingEntry?>>> = runCatchingApp {
        api.getTrending(page, perPage, priority).map { dto -> dto.toWorkMatch() to dto.toRatingEntry() }
    }

    suspend fun getAiringSchedules(
        fromEpochSeconds: Long,
        toEpochSeconds: Long?,
        priority: RequestPriority = RequestPriority.BACKGROUND,
    ): Result<List<BroadcastScheduleEntry>> = runCatchingCancellable {
        val entries = mutableListOf<BroadcastScheduleEntry>()
        var page = 1
        do {
            val response = api.getAiringSchedules(fromEpochSeconds, toEpochSeconds, page, priority = priority)
            response.schedules.forEach { schedule ->
                val media = schedule.media ?: return@forEach
                if (media.type != AniListMediaType.ANIME || media.format !in VALID_FORMATS) return@forEach
                val airingMillis = schedule.airingAt * MILLIS_PER_SECOND
                val day = Instant.ofEpochMilli(airingMillis).atZone(ZoneId.systemDefault()).dayOfWeek
                entries += BroadcastScheduleEntry(
                    scheduleId = "ANILIST_SCHEDULE:${schedule.id}",
                    workId = "ANILIST:${media.id}",
                    sourceItemId = media.id.toString(),
                    // Keep the native title first so Bangumi's canonical Japanese title can be
                    // matched without making an English title the schedule's identity.
                    title = media.title?.nativeTitle
                        ?: media.title?.romaji
                        ?: media.title?.english
                        ?: media.id.toString(),
                    titleVariants = listOfNotNull(
                        media.title?.nativeTitle,
                        media.title?.romaji,
                        media.title?.english,
                    ).distinct(),
                    coverUrl = media.coverImage?.medium ?: media.coverImage?.large,
                    airingAtUtcMillis = airingMillis,
                    dayOfWeek = day,
                    episode = schedule.episode,
                )
            }
            page += 1
        } while (response.pageInfo?.hasNextPage == true && page <= MAX_SCHEDULE_PAGES)
        entries.distinctBy { it.scheduleId }
    }

    private companion object {
        val VALID_FORMATS = setOf("TV", "TV_SHORT", "ONA")
        const val MAX_SCHEDULE_PAGES = 4
        const val MILLIS_PER_SECOND = 1_000L
    }
}
