package com.acgcompass.data.remote.mal

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch

/**
 * MAL 官方 API DTO → 领域模型映射。
 *
 * 原则（不伪造，缺失即缺失，与 `JikanMappers` / `BangumiMappers` 一致）：
 * - 缺失字段以 `null` / 空集合透传，由 UI 渲染「暂无数据」。
 * - 评分缺失（`mean` 为 `null` 或 `num_scoring_users<=0`）时 [MalAnimeDto.toRatingEntry] 返回 `null`，
 *   **绝不**以 0 分伪造，也绝不跨源回填其它源数据。
 * - MAL 的 `media_type`/`status` 取值与 Jikan 不同（小写下划线风格），未知 / 缺失安全兜底。
 */

/** MAL 动画 `media_type` → 领域 [MediaType]；anime 端点只返回动画，未知 / 缺失回退 [MediaType.ANIME]。 */
fun mapMalMediaType(mediaType: String?): MediaType = when (mediaType?.lowercase()) {
    MalMediaType.TV,
    MalMediaType.MOVIE,
    MalMediaType.OVA,
    MalMediaType.ONA,
    MalMediaType.SPECIAL,
    MalMediaType.MUSIC,
    MalMediaType.UNKNOWN,
    -> MediaType.ANIME
    else -> MediaType.ANIME
}

/** MAL 动画 `status` → 领域 [ReleaseStatus]；未知 / 缺失回退 [ReleaseStatus.UNKNOWN]（不臆造）。 */
fun mapMalStatus(status: String?): ReleaseStatus = when (status?.lowercase()) {
    MalAnimeStatus.FINISHED -> ReleaseStatus.FINISHED
    MalAnimeStatus.AIRING -> ReleaseStatus.RELEASING
    MalAnimeStatus.NOT_YET_AIRED -> ReleaseStatus.NOT_RELEASED
    else -> ReleaseStatus.UNKNOWN
}

/** 选取首播年份：取 `start_season.year`（限合理区间）；缺失返回 `null`（不臆造）。 */
internal fun MalAnimeDto.resolvedYear(): Int? = startSeason?.year?.takeIf { it in 1900..2999 }

/** 选取封面 URL：优先 `main_picture.large`，其次 `medium`；全无返回 `null`（不臆造）。 */
internal fun MalPictureDto?.preferredCover(): String? {
    if (this == null) return null
    return large?.takeIf { it.isNotBlank() } ?: medium?.takeIf { it.isNotBlank() }
}

/**
 * 动画 DTO → [RatingEntry]（MAL 官方评分）；当无有效样本（`mean` 缺失或 `num_scoring_users<=0`）
 * 时返回 `null` 表示「暂无数据」，不以 0 分伪造评分。[rank] 仅在为正时保留。
 */
fun MalAnimeDto.toRatingEntry(): RatingEntry? {
    val s = mean ?: return null
    if (s <= 0f) return null
    val votes = numScoringUsers ?: 0
    if (votes <= 0) return null
    return RatingEntry(
        score = s,
        voteCount = votes,
        rank = rank?.takeIf { it > 0 },
    )
}

/**
 * 动画 DTO → 规范化 [Work]。
 *
 * 标题：[Titles.canonical] 取主标题 `title`（MAL 以罗马音 / 英文为主，缺失回退英文名 / 日文名 / id）；
 * [Titles.ja] 取 `alternative_titles.ja`，[Titles.en] 取 `alternative_titles.en`，别名取 `synonyms`（过滤空白）。
 */
fun MalAnimeDto.toWork(): Work {
    val primary = title.takeIf { it.isNotBlank() }
    val english = alternativeTitles?.en?.takeIf { it.isNotBlank() }
    val japanese = alternativeTitles?.ja?.takeIf { it.isNotBlank() }
    val canonical = primary ?: english ?: japanese ?: id.toString()
    val aliases = alternativeTitles?.synonyms.orEmpty().filter { it.isNotBlank() }
    return Work(
        id = id.toString(),
        titles = Titles(
            canonical = canonical,
            ja = japanese,
            en = english,
            aliases = aliases,
        ),
        mediaType = mapMalMediaType(mediaType),
        year = resolvedYear(),
        status = mapMalStatus(status),
        units = Units(
            episodes = numEpisodes?.takeIf { it > 0 },
        ),
        coverUrl = mainPicture.preferredCover(),
        primarySource = SourceId.MAL,
        tags = emptyList(),
    )
}

/**
 * 动画 DTO → [WorkMatch]（搜索 / 匹配结果）。
 *
 * @param matchConfidence 由匹配器给出的置信度 ∈ [0,1]；直接 `getAnime` 视为精确命中（默认 `1f`）。
 */
fun MalAnimeDto.toWorkMatch(matchConfidence: Float = 1f): WorkMatch = WorkMatch(
    work = toWork(),
    matchConfidence = matchConfidence,
    sourceTag = SourceId.MAL,
)

/** 用户列表项 → [WorkMatch]（仅展开作品节点；个人进度 / 评分由上层另行读取 `list_status`）。 */
fun MalUserListNodeDto.toWorkMatchOrNull(): WorkMatch? = node?.toWorkMatch()

/** 搜索结果项 → [WorkMatch]（展开 `node` 作品节点；节点缺失返回 `null`，不臆造）。 */
fun MalAnimeListNodeDto.toWorkMatchOrNull(): WorkMatch? = node?.toWorkMatch()
