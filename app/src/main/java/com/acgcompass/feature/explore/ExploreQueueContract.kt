package com.acgcompass.feature.explore

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.displayTitle
import com.acgcompass.domain.taste.TasteCategory
import com.acgcompass.domain.taste.TasteMatchResult
import com.acgcompass.domain.taste.TasteVerdict

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
    /** 预计评分一句话（如「预计你会打 7.8 · 社区 7.2」）；模型不可用时为 null。 */
    val predictionText: String? = null,
    /** 跨界候选：口碑扎实但题材在画像常看范围之外，卡片上以「换个口味」标识。 */
    val discovery: Boolean = false,
)

/**
 * 将 12 维匹配结果和作品映射为探索卡片；理由只呈现题材、组合和情节维度，
 * 引擎无结果时退化为「探索尝试 / 按热度」措辞（绝不伪造匹配度）。
 */
internal fun Work.toExploreCard(
    match: TasteMatchResult?,
    discovery: Boolean = false,
    prefsNote: String? = null,
): ExploreCardUiModel {
    val parts = listOfNotNull(
        units.episodes?.let { "$it 话" },
        year?.toString(),
        mediaTypeLabel(mediaType),
        prefsNote,
    )
    val reason = exploreReason(match, discovery)
    val score = match?.score
    return ExploreCardUiModel(
        workId = id,
        coverUrl = coverUrl,
        title = displayTitle(),
        meta = parts.joinToString(" · "),
        tastePercent = score?.let { "$it%" } ?: "—",
        tasteFraction = (score ?: 0) / 100f,
        tasteQualitative = if (score == null) {
            "探索推荐"
        } else {
            TasteVerdict.of(score, match.prediction?.rating, match.prediction?.isUncertain == true)
        },
        reason = reason,
        tags = tags.take(5).map { it.name },
        synopsis = summary?.trim()?.takeIf { it.isNotBlank() },
        discovery = discovery,
        predictionText = match?.prediction?.let { p ->
            "预计你会打 ${p.headline} 分" + p.community?.let { " · 社区 %.1f".format(it) }.orEmpty()
        },
    )
}

/** 卡片上的一句话推荐理由。只引用正向贡献的维度：负向项是「拉低匹配」的原因，不能写成「命中」。 */
private fun exploreReason(match: TasteMatchResult?, discovery: Boolean): String {
    val positive = match?.positiveReasons.orEmpty()
    val topicReasons = positive.filter {
        it.category == TasteCategory.TOPIC ||
            it.category == TasteCategory.COMBO ||
            it.category == TasteCategory.DEVICE
    }.ifEmpty { positive }
    return when {
        discovery -> {
            val rating = match?.communityScore10?.let { "社区评分 %.1f，".format(it) }.orEmpty()
            "${rating}口碑扎实，题材和你常看的不一样，可以换换口味"
        }
        topicReasons.isNotEmpty() ->
            "命中「${topicReasons.take(MAX_REASON_LABELS).joinToString(" + ") { it.label }}」，与长期口味画像接近"
        match != null -> "和你常看的不太一样，可以试试"
        else -> "口味画像尚未就绪，先按热度作为探索推荐"
    }
}

private const val MAX_REASON_LABELS = 3

private fun mediaTypeLabel(t: MediaType): String = when (t) {
    MediaType.ANIME -> "动画"
    MediaType.MANGA -> "漫画"
    MediaType.NOVEL -> "轻小说"
    else -> "作品"
}
