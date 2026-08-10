package com.acgcompass.data.remote.vndb

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.visualnovel.VisualNovelCredit
import com.acgcompass.domain.visualnovel.VisualNovelDetail
import com.acgcompass.domain.visualnovel.VisualNovelImage
import com.acgcompass.domain.visualnovel.VisualNovelRelation
import com.acgcompass.domain.visualnovel.VisualNovelTag
import com.acgcompass.domain.visualnovel.VisualNovelVoiceRole

/**
 * VNDB DTO → 领域模型映射。
 *
 * 原则（不伪造，缺失即缺失，与 `JikanMappers` / `BangumiMappers` 一致）：
 * - 缺失字段以 `null` / 空集合透传，由 UI 渲染「暂无数据」。
 * - 评分缺失（`rating==null` 或 `votecount<=0`）时 [VndbVnDto.toRatingEntry] 返回 `null`，
 *   **保留 10–100 源标度**（聚合时由 `AggregateRatingsUseCase.normalizeToTen` 归一），
 *   绝不以 0 伪造评分，也绝不跨源回填。
 * - **成人内容分级**：依据 `image.sexual`（0–2 均值），[VndbVnDto.isAdult]
 *   判定是否成人内容；过滤/隐藏逻辑在 [VndbRemoteDataSource] 按 `showAdultContent` 开关执行。
 */

/** `image.sexual`（0–2 均值）达到该阈值即视为成人内容（1=暗示 / 2=露骨）。 */
const val VNDB_ADULT_SEXUAL_THRESHOLD: Float = 1.0f

/** 是否为成人内容：当 `image.sexual >= [VNDB_ADULT_SEXUAL_THRESHOLD]` 时为 `true`（无图/无评分视为非成人）。 */
fun VndbVnDto.isAdult(): Boolean {
    if ((image?.sexual ?: 0f) >= VNDB_ADULT_SEXUAL_THRESHOLD) return true
    if (screenshots.any { (it.sexual ?: 0f) >= VNDB_ADULT_SEXUAL_THRESHOLD }) return true
    return tags.any { it.category == "ero" && !it.lie && it.rating > 0f }
}

/** 从 VNDB 发行日期串（`"YYYY-MM-DD"`/`"YYYY-MM"`/`"YYYY"`/`"TBA"`）解析年份；无法解析返回 `null`（不臆造）。 */
internal fun parseVndbYear(released: String?): Int? {
    val head = released?.take(4) ?: return null
    return head.toIntOrNull()?.takeIf { it in 1900..2999 }
}

/** 选取原文（日文等）标题：优先与 `olang` 同语言的标题，其次主标题；全无返回 `null`。 */
internal fun VndbVnDto.originalScriptTitle(): String? {
    val byOlang = olang?.let { lang -> titles.firstOrNull { it.lang == lang } }
    val main = titles.firstOrNull { it.main }
    return (byOlang ?: main)?.title?.takeIf { it.isNotBlank() } ?: altTitle?.takeIf { it.isNotBlank() }
}

/** 选取罗马音标题：优先 `olang`/主标题的 `latin`；全无返回 `null`。 */
internal fun VndbVnDto.romajiTitle(): String? {
    val byOlang = olang?.let { lang -> titles.firstOrNull { it.lang == lang } }
    val main = titles.firstOrNull { it.main }
    return (byOlang ?: main)?.latin?.takeIf { it.isNotBlank() }
}

/** Display title priority: Simplified Chinese, Traditional Chinese, Chinese alias, original script, main title. */
internal fun VndbVnDto.preferredDisplayTitle(): String? {
    val simplified = titles.firstOrNull { (it.lang ?: "").lowercase() in SIMPLIFIED_CHINESE_LANGUAGE_TAGS }
        ?.title?.takeIf(String::isNotBlank)
    val traditional = titles.firstOrNull { (it.lang ?: "").lowercase() in TRADITIONAL_CHINESE_LANGUAGE_TAGS }
        ?.title?.takeIf(String::isNotBlank)
    val chineseAlias = aliases.firstOrNull { value -> value.any { it in '\u4E00'..'\u9FFF' } }
    val main = title?.takeIf(String::isNotBlank)
    return simplified ?: traditional ?: chineseAlias ?: originalScriptTitle() ?: main
}

/**
 * VN DTO → [RatingEntry]（VNDB 贝叶斯评分，**保留 10–100 源标度**）；当无有效样本
 * （`rating==null` 或 `votecount<=0`）时返回 `null` 表示「暂无数据」，不以 0 伪造评分
 * 。VNDB 不提供单独排名，[RatingEntry.rank] 恒为 `null`。
 */
fun VndbVnDto.toRatingEntry(): RatingEntry? {
    val r = rating ?: return null
    val votes = voteCount ?: 0
    if (r <= 0f || votes <= 0) return null
    return RatingEntry(
        score = r,
        voteCount = votes,
        rank = null,
    )
}

/**
 * VN DTO → 规范化 [Work]（[MediaType.VN]）。
 *
 * 标题：[Titles.canonical] 按中文、原文、VNDB 主标题的顺序选择，全部缺失时回退 id；
 * [Titles.ja] 取原文（`olang`）标题，[Titles.romaji] 取其罗马音，别名取 `aliases`（过滤空白）。
 * 体量：VN 用 [Units.estPlayMinutes]（取 `length_minutes` 实测均值，缺失 / 非正为 `null`）。
 * VNDB 不提供清晰的发行状态字段，[Work.status] 取 [ReleaseStatus.UNKNOWN]（不臆造）。
 */
fun VndbVnDto.toWork(): Work {
    val original = originalScriptTitle()
    val canonical = preferredDisplayTitle() ?: id
    return Work(
        id = id,
        titles = Titles(
            canonical = canonical,
            ja = original,
            romaji = romajiTitle(),
            en = null,
            aliases = aliases.filter { it.isNotBlank() },
        ),
        mediaType = MediaType.VN,
        year = parseVndbYear(released),
        status = when (developmentStatus) {
            0 -> ReleaseStatus.FINISHED
            1 -> ReleaseStatus.RELEASING
            2 -> ReleaseStatus.CANCELLED
            else -> ReleaseStatus.UNKNOWN
        },
        units = Units(
            estPlayMinutes = lengthMinutes?.takeIf { it > 0 },
        ),
        coverUrl = image?.thumbnail?.takeIf { it.isNotBlank() } ?: image?.url,
        primarySource = SourceId.VNDB,
        tags = tags.asSequence()
            .filter { !it.lie && it.spoiler == 0 && it.name.isNotBlank() }
            .sortedByDescending { it.rating }
            .take(30)
            .map { Tag(TagCategory.CONTENT, it.name) }
            .toList(),
        summary = description?.takeIf { it.isNotBlank() },
    )
}

/**
 * VN DTO → [WorkMatch]（搜索 / 匹配结果）。
 *
 * @param matchConfidence 由匹配器给出的置信度 ∈ [0,1]；直接 `getVn` 视为精确命中（默认 `1f`）。
 */
fun VndbVnDto.toWorkMatch(matchConfidence: Float = 1f): WorkMatch = WorkMatch(
    work = toWork(),
    matchConfidence = matchConfidence,
    sourceTag = SourceId.VNDB,
)

fun VndbVnDto.toVisualNovelDetail(): VisualNovelDetail = VisualNovelDetail(
    id = id,
    title = preferredDisplayTitle() ?: id,
    originalTitle = originalScriptTitle(),
    description = description?.takeIf { it.isNotBlank() },
    released = released,
    languages = languages.distinct(),
    platforms = platforms.distinct(),
    rating = rating,
    voteCount = voteCount ?: 0,
    lengthMinutes = lengthMinutes?.takeIf { it > 0 },
    cover = image?.toDomainImage(),
    screenshots = screenshots.mapNotNull { it.toDomainImage() },
    tags = tags.asSequence()
        .filter { !it.lie && it.name.isNotBlank() }
        .sortedByDescending { it.rating }
        .map { VisualNovelTag(it.id, it.name, it.rating, it.spoiler, it.category) }
        .toList(),
    developers = developers.map { VisualNovelCredit(it.id, it.name, it.original) },
    staff = staff.map { VisualNovelCredit(it.id, it.name, it.original, it.role, it.note) },
    voiceRoles = voiceActors.mapNotNull { role ->
        val actor = role.staff ?: return@mapNotNull null
        val character = role.character ?: return@mapNotNull null
        VisualNovelVoiceRole(character.id, character.name, actor.id, actor.name)
    },
    relations = relations.map { relation ->
        VisualNovelRelation(
            id = relation.id,
            title = relation.title.ifBlank { relation.altTitle.orEmpty().ifBlank { relation.id } },
            relation = relation.relation,
            official = relation.official,
            rating = relation.rating,
            voteCount = relation.voteCount ?: 0,
        )
    },
    adult = isAdult(),
)

private val SIMPLIFIED_CHINESE_LANGUAGE_TAGS = setOf("zh-hans", "zh-cn", "zh")
private val TRADITIONAL_CHINESE_LANGUAGE_TAGS = setOf("zh-hant", "zh-tw", "zh-hk")

private fun VndbImageDto.toDomainImage(): VisualNovelImage? {
    val imageUrl = url?.takeIf { it.isNotBlank() } ?: return null
    return VisualNovelImage(
        id = id,
        url = imageUrl,
        thumbnailUrl = thumbnail,
        width = dimensions.getOrNull(0),
        height = dimensions.getOrNull(1),
        thumbnailWidth = thumbnailDimensions.getOrNull(0),
        thumbnailHeight = thumbnailDimensions.getOrNull(1),
        sexual = sexual ?: 0f,
        violence = violence ?: 0f,
    )
}
