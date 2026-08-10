package com.acgcompass.domain.model

/**
 * 作品的媒介类型。
 */
enum class MediaType {
    ANIME,
    MANGA,
    NOVEL,
    GAME,
    VN,

    /** 音乐、真人和未知等非核心类型，避免误判为动画进入发现、推荐和口味池。 */
    OTHER,
    ;

    companion object {
        /** 从持久化字符串解析；未知值回退到 `null`，由调用方决定兜底。 */
        fun fromStorage(raw: String?): MediaType? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * 作品发行状态。包含 [UNKNOWN] 以便对未知 / 缺失数据安全兜底，
 * 避免在「暂无数据」场景抛出异常。
 */
enum class ReleaseStatus {
    UNKNOWN,
    NOT_RELEASED,
    RELEASING,
    FINISHED,
    ON_HIATUS,
    CANCELLED,
    ;

    companion object {
        /** 从持久化字符串解析；未知 / `null` 一律回退为 [UNKNOWN]，保证非空且不崩溃。 */
        fun fromStorage(raw: String?): ReleaseStatus = entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

/**
 * 补完成本分类：今晚可看完 / 周末 / 长期坑。
 */
enum class CompletionCost {
    TONIGHT,
    WEEKEND,
    LONG_HAUL,
    ;

    companion object {
        /** 从持久化字符串解析；未知值返回 `null`（成本未知时 UI 显示「暂无数据」）。 */
        fun fromStorage(raw: String?): CompletionCost? = entries.firstOrNull { it.name == raw }
    }
}

/**
 * 多语言标题集合。[canonical] 为本地规范化标题（必有）；其余语言名与别名可缺失。
 * 别名 [aliases] 用于标题归一化与多源匹配。
 */
data class Titles(
    val canonical: String,
    /** 中文标题（如 Bangumi name_cn）。卡片/列表统一中文优先展示；缺失为 null。 */
    val cn: String? = null,
    val ja: String? = null,
    val romaji: String? = null,
    val en: String? = null,
    val aliases: List<String> = emptyList(),
)

/**
 * 体量 / 时长单位。各字段按媒介类型各取所需，缺失即为 `null`（UI 显示「暂无数据」）。
 * - 动画：[episodes] + [episodeMinutes]。
 * - 漫画 / 小说：[volumes]。
 * - 游戏 / VN：[estPlayMinutes]（预计游玩时长）。
 */
data class Units(
    val episodes: Int? = null,
    val episodeMinutes: Int? = null,
    val volumes: Int? = null,
    val estPlayMinutes: Int? = null,
)

/**
 * 规范化作品（canonical Work）—— 领域层的核心模型，UI / 用例操作的对象（纯 Kotlin，无 Android 依赖）。
 *
 * 多源标识通过 [SourceRef] / `SourceLink` 关联到各外部源；评分聚合见 [RatingAggregate]。
 * 缺失字段（如 [year]、[coverUrl]、[completionCost]）以 `null` 表示，由 UI 渲染为「暂无数据」
 * 而非伪造。
 *
 * 注意：持久化用的 `createdAt` / `updatedAt` 时间戳属于 Entity 层关注点，不进入领域模型。
 */
data class Work(
    val id: String,
    val titles: Titles,
    val mediaType: MediaType,
    val year: Int? = null,
    val status: ReleaseStatus = ReleaseStatus.UNKNOWN,
    val units: Units = Units(),
    val coverUrl: String? = null,
    val primarySource: SourceId,
    val completionCost: CompletionCost? = null,
    val tags: List<Tag> = emptyList(),
    /**
     * 作品简介 / 梗概（来自主源条目详情，如 Bangumi `summary`）。缺失时为 `null`，
     * 缺失时由 UI 渲染「暂无数据」；该字段是详情页简介的数据来源。
     */
    val summary: String? = null,
    /**
     * 精确到天的开播 / 发行日期（`yyyy-MM-dd`，来自主源如 Bangumi `date`）。缺失为 `null`。
     */
    val airDate: String? = null,
)

/** 展示用标题——中文优先（[Titles.cn] 回退 [Titles.canonical]）。卡片/列表统一调用以保证中文优先展示。 */
fun Work.displayTitle(): String = titles.cn ?: titles.canonical
