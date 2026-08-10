package com.acgcompass.feature.library

import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Work

/**
 * 「我的库 / 我的收藏」页 UI 契约。
 *
 * 信息架构：
 * - **我的库** = 全部同步 / 本地记录的作品（想看 / 在看 / 看过 / 搁置 / 抛弃），来源 `user_collections`（+ 本地）。
 * - **待补池** = 用户主动加入、准备以后补的作品（backlog）；我的库条目可手动「加入待补池」。
 * - **已看列表** = 我的库中状态为「看过」的作品，**不属于**待补池。
 *
 * 本契约把 `UserCollectionEntity` + `Work` 折叠为可直接渲染的 [LibraryItem]，映射逻辑为纯函数便于测试。
 * 缺失字段统一显示「[NO_DATA]」，绝不伪造。
 */

/** 字段缺失统一兜底文案。 */
const val NO_DATA: String = "暂无数据"

/**
 * 我的库状态分组；[status] 为 `null` 表示全部。
 */
enum class LibraryStatusTab(val key: String, val label: String, val status: String?) {
    ALL("all", "全部", null),
    WANT("want", "想看", "想看"),
    WATCHING("watching", "在看", "在看"),
    WATCHED("watched", "看过", "看过"),
    ON_HOLD("on_hold", "搁置", "搁置"),
    DROPPED("dropped", "抛弃", "抛弃"),
    ;

    companion object {
        /** 由路由参数 key 解析初始 Tab；未知 / `null` 回退「全部」。 */
        fun fromKey(key: String?): LibraryStatusTab = entries.firstOrNull { it.key == key } ?: ALL
    }
}

/**
 * 我的库单条目，包含可直接渲染的展示字段。
 *
 * @property workId 本地作品 id（= Bangumi subjectId）。
 * @property title 标题（缺失作品时回退「暂无数据」）。
 * @property coverUrl 封面；`null` 时由卡片渲染占位。
 * @property typeText 类型文案（动画 / 漫画 …）；缺失「暂无数据」。
 * @property yearText 年份文案；缺失「暂无数据」。
 * @property status 我的状态（想看 / 在看 / 看过 / 搁置 / 抛弃）；缺失为 `null`。
 * @property ratingText 我的评分文案（如「9 / 10」）；缺失为 `null`。
 * @property progressText 进度文案；缺失为 `null`。
 * @property sourceText 来源（如「Bangumi」）。
 * @property inBacklog 是否已在待补池。
 * @property canAddToBacklog 是否可「加入待补池」（想看 / 在看 且未在池中）。
 */
data class LibraryItem(
    val workId: String,
    val title: String,
    val coverUrl: String?,
    val typeText: String,
    val yearText: String,
    val status: String?,
    val ratingText: String?,
    val progressText: String?,
    val sourceText: String,
    val inBacklog: Boolean,
    val canAddToBacklog: Boolean,
)

/**
 * 我的库聚合数据。
 *
 * @property items 全部条目（未按 Tab 过滤；按最近同步降序）。
 * @property counts 各 Tab 的条目数（含「全部」）。
 */
data class MyLibraryData(
    val items: List<LibraryItem>,
    val counts: Map<LibraryStatusTab, Int>,
) {
    /** 取某 Tab 下的条目（[LibraryStatusTab.ALL] 返回全部）。 */
    fun itemsFor(tab: LibraryStatusTab): List<LibraryItem> =
        if (tab.status == null) items else items.filter { it.status == tab.status }
}

/** 来源名（`UserCollectionEntity.source` 为 SourceId 名）→ 可读名。 */
fun librarySourceLabel(source: String): String = when (source.uppercase()) {
    "BANGUMI" -> "Bangumi"
    "ANILIST" -> "AniList"
    "JIKAN" -> "Jikan"
    "MAL" -> "MyAnimeList"
    "VNDB" -> "VNDB"
    else -> source
}

/** 媒介类型 → 可读类型文案；`null`（未匹配本地作品）回退「暂无数据」。 */
fun libraryTypeLabel(mediaType: MediaType?): String = when (mediaType) {
    MediaType.ANIME -> "动画"
    MediaType.MANGA -> "漫画"
    MediaType.NOVEL -> "小说"
    MediaType.GAME -> "游戏"
    MediaType.VN -> "视觉小说"
    MediaType.OTHER -> "其他"
    null -> NO_DATA
}

/** 可加入待补池的状态为想看或在看。 */
private val ADDABLE_STATUSES = setOf("想看", "在看")

/**
 * 把单条收藏（已解构为基本字段）与可选的本地 [Work] 折叠为 [LibraryItem]（纯函数，便于测试）。
 *
 * @param workId 本地作品 id。
 * @param status 我的状态；缺失为 `null`。
 * @param rating 我的评分（1–10）；缺失为 `null`。
 * @param progress 进度；缺失为 `null`。
 * @param source 来源（SourceId 名）。
 * @param work 本地规范化作品；`null` 表示尚未入库该作品（标题 / 类型 / 年份显示「暂无数据」）。
 * @param inBacklog 是否已在待补池。
 */
fun buildLibraryItem(
    workId: String,
    status: String?,
    rating: Int?,
    progress: Int?,
    source: String,
    work: Work?,
    inBacklog: Boolean,
): LibraryItem {
    val cleanStatus = status?.takeIf { it.isNotBlank() }
    return LibraryItem(
        workId = workId,
        title = work?.titles?.canonical?.takeIf { it.isNotBlank() } ?: NO_DATA,
        coverUrl = work?.coverUrl,
        typeText = libraryTypeLabel(work?.mediaType),
        yearText = work?.year?.toString() ?: NO_DATA,
        status = cleanStatus,
        ratingText = rating?.takeIf { it in 1..10 }?.let { "$it / 10" },
        progressText = progress?.let { progressLabel(work?.mediaType, it) },
        sourceText = librarySourceLabel(source),
        inBacklog = inBacklog,
        canAddToBacklog = cleanStatus in ADDABLE_STATUSES && !inBacklog,
    )
}

/** 进度量词按媒介类型选择（动画=集，漫画/小说=话，其余=进度）。 */
private fun progressLabel(mediaType: MediaType?, progress: Int): String = when (mediaType) {
    MediaType.ANIME -> "已看 $progress 集"
    MediaType.MANGA, MediaType.NOVEL -> "已读 $progress 话"
    else -> "进度 $progress"
}

/** 折叠为统一作品卡片模型（复用 [com.acgcompass.core.designsystem.WorkCard]）。 */
fun LibraryItem.toWorkCardUiModel(): WorkCardUiModel {
    val subtitle = yearText.takeIf { it != NO_DATA }.orEmpty()
    val rating = ratingText?.let { "我的 $it" }
    val statusLine = listOfNotNull(
        status?.let { "我的库 · $it" },
        progressText,
    ).joinToString(" · ").takeIf { it.isNotEmpty() }
    return WorkCardUiModel(
        coverUrl = coverUrl,
        title = title,
        subtitle = subtitle,
        type = typeText,
        ratingText = rating,
        sourceTags = listOf(sourceText),
        backlogStatus = statusLine,
        completionCost = if (inBacklog) "已在待补池" else null,
    )
}
