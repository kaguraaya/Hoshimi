package com.acgcompass.feature.explore

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.displayTitle
import com.acgcompass.domain.taste.TasteCategory
import com.acgcompass.domain.taste.TasteMatchResult

/**
 * 探索队列 UI 契约。
 *
 * 主动探索「你可能喜欢、但还没接触」的作品：候选 = 本地 + 公共池动画 − 已收藏 − 待补池 − 近期曝光；
 * **口味匹配度（12 维引擎）为核心**排序与展示，社区评分仅参考（见详情页，刻意弱化以突出个性化）。
 * 右滑加入待补池、左滑暂不感兴趣（记曝光冷却，短期不再重复推荐）。
 */
sealed interface ExploreQueueUiState {
    /** 生成中（首屏 / 再来一批）。 */
    data object Loading : ExploreQueueUiState

    /** 正在为候选补齐真实特征；达到最少可用卡片数后自动进入队列。 */
    data class Preparing(val completed: Int, val total: Int) : ExploreQueueUiState

    /** 无候选（本地池空 / 都已浏览过）——给出可执行引导，不伪造内容。 */
    data class Empty(val message: String) : ExploreQueueUiState

    /** 生成失败（异常兜底）。 */
    data class Error(val message: String) : ExploreQueueUiState

    /** 一批卡片就绪，[index] 为当前展示位置。 */
    data class Ready(val cards: List<ExploreCardUiModel>, val index: Int) : ExploreQueueUiState

    /** 一批浏览完毕，给出本批统计，可「再来一批」。 */
    data class Finished(val liked: Int, val skipped: Int) : ExploreQueueUiState
}

/** 单张探索卡片：口味匹配度为视觉核心，社区评分弱化到详情页（核心差异化）。 */
data class ExploreCardUiModel(
    val workId: String,
    val coverUrl: String?,
    val title: String,
    val meta: String,
    val tastePercent: String,
    val tasteFraction: Float,
    val tasteQualitative: String,
    val reason: String,
    val tags: List<String>,
    /** 主源作品简介；缺失时为 `null`，卡片背面显示暂无简介。 */
    val synopsis: String?,
    /** 背面简介懒加载中标记——翻面时若无本地简介则联网补齐，加载期间背面显示「加载中」。 */
    val synopsisLoading: Boolean = false,
)

/**
 * 将 12 维匹配结果和作品映射为探索卡片；理由只呈现题材、组合和情节维度，
 * 引擎无结果时退化为「探索尝试 / 按热度」措辞（绝不伪造匹配度）。
 */
internal fun Work.toExploreCard(match: TasteMatchResult?): ExploreCardUiModel {
    val parts = listOfNotNull(
        units.episodes?.let { "$it 话" },
        year?.toString(),
        mediaTypeLabel(mediaType),
    )
    val topicReasons = match?.reasons.orEmpty().filter {
        it.category == TasteCategory.TOPIC ||
            it.category == TasteCategory.COMBO ||
            it.category == TasteCategory.DEVICE
    }.ifEmpty { match?.reasons.orEmpty() }
    val reason = when {
        topicReasons.isNotEmpty() ->
            "命中「${topicReasons.take(3).joinToString(" + ") { it.label }}」，与长期口味画像接近"
        match != null -> "与口味画像重合较少，作为探索性候选"
        else -> "口味画像尚未就绪，先按热度作为探索推荐"
    }
    val score = match?.score
    return ExploreCardUiModel(
        workId = id,
        coverUrl = coverUrl,
        title = displayTitle(),
        meta = parts.joinToString(" · "),
        tastePercent = score?.let { "$it%" } ?: "—",
        tasteFraction = (score ?: 0) / 100f,
        tasteQualitative = when {
            score == null -> "探索推荐"
            score >= 80 -> "与口味画像高度匹配"
            score >= 65 -> "可能会喜欢"
            score >= 45 -> "可能感觉一般"
            else -> "可以试试看"
        },
        reason = reason,
        tags = tags.take(5).map { it.name },
        synopsis = summary?.trim()?.takeIf { it.isNotBlank() },
    )
}

private fun mediaTypeLabel(t: MediaType): String = when (t) {
    MediaType.ANIME -> "动画"
    MediaType.MANGA -> "漫画"
    MediaType.NOVEL -> "轻小说"
    else -> "作品"
}
