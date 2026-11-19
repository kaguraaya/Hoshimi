package com.acgcompass.feature.discover

import androidx.annotation.VisibleForTesting
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.Cta
import com.acgcompass.domain.matching.MATCH_THRESHOLD
import com.acgcompass.domain.matching.allTitleVariants
import com.acgcompass.domain.matching.clusterMatches
import com.acgcompass.domain.matching.representativeOf
import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.SourceRef
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkIds
import com.acgcompass.domain.model.WorkMatch
import com.acgcompass.domain.model.displayTitle
import kotlin.math.roundToInt

/**
 * 发现 / 搜索页（Discover_Screen / Search_Module）的 UI 契约。
 *
 * 把领域层的多源搜索结果（[WorkMatch]）折叠为表现层可直接渲染的纯数据模型，使界面与领域层解耦、
 * 可独立预览与单元测试。映射逻辑为 **纯函数**（无 Android / IO 依赖）。
 *
 * 核心不变式：
 * - **来源标签 + Match_Confidence 旁标**：每条结果在卡片来源 chips 上同时
 *   标注数据来源（如 Bangumi）与匹配置信度（如「匹配度 92%」）。
 * - **低置信手动纠正**：当 [WorkMatch.matchConfidence] 低于
 *   [MATCH_THRESHOLD] 时标记 [DiscoverResultItem.isLowConfidence]，界面据此展示「手动选择正确条目」
 *   入口，用户确认后调用 `WorkRepository.overrideMatch`。
 * - **缺失即标记、绝不伪造**：评分等暂不可得字段为 `null`，由
 *   [com.acgcompass.core.designsystem.WorkCard] 显示「暂无数据」。
 *
 * 本契约覆盖搜索和低置信纠正；榜单、评分差异榜与高级筛选使用独立状态模型。
 */

/**
 * 一条搜索结果。携带渲染所需的卡片模型与手动纠正所需的源引用信息。
 *
 * @property workId 命中的规范化作品 id，供点击进入详情（`onOpenWork(workId)`）。
 * @property card 作品卡片展示模型（来源标签上已附置信度）。
 * @property isLowConfidence 是否为低置信结果（< [MATCH_THRESHOLD]），界面据此展示手动纠正入口。
 * @property sourceId 产生该结果的数据源（手动纠正写入 `SourceRef.sourceId`）。
 * @property sourceItemId 源内条目 id（当前以作品 id 兜底，写入 `SourceRef.sourceItemId`）。
 * @property matchConfidence 匹配置信度 ∈ [0,1]。
 */
data class DiscoverResultItem(
    val workId: String,
    val card: WorkCardUiModel,
    val isLowConfidence: Boolean,
    val sourceId: SourceId,
    val sourceItemId: String,
    val matchConfidence: Float,
    /**
     * 跨源合并后该卡聚合的来源候选。单源结果为空列表；多源合并时包含各来源的单项
     * [DiscoverResultItem]，供「手动纠正 / 手动拆分」选择正确条目。
     */
    val mergeCandidates: List<DiscoverResultItem> = emptyList(),
)

/** 初始 / 空查询态的「下一步」引导：提示用户输入关键词搜索。 */
internal val SEARCH_CTA: Cta = Cta(label = "输入关键词开始搜索", action = DiscoverAction.FOCUS_SEARCH)

/** 有查询但无结果时的「下一步」引导：换个关键词再试。 */
internal val NO_RESULT_CTA: Cta = Cta(label = "换个关键词再试试", action = DiscoverAction.FOCUS_SEARCH)

/** 榜单空态引导（换范围或检查网络）。 */
internal val NO_RANKING_CTA: Cta = Cta(label = "换个范围或检查网络后重试", action = DiscoverAction.FOCUS_SEARCH)

/** 空态友好文案：搜索框支持中文 / 日文 / 罗马音 / 英文 / 别名。 */
internal const val SEARCH_EMPTY_MESSAGE: String = "搜索作品：支持中文 / 日文 / 罗马音 / 英文 / 别名"

/** 无结果友好文案。 */
internal const val NO_RESULT_MESSAGE: String = "没有找到匹配的作品"

/** 发现页内部动作标识（空态 CTA 等用以触发界面行为）。 */
internal object DiscoverAction {
    const val FOCUS_SEARCH = "focus_search"
}

// region 领域 → UI 映射（纯函数）

/**
 * 把一条多源搜索结果 [WorkMatch] 折叠为 [DiscoverResultItem]。
 *
 * 来源标签 chips 同时承载数据来源与 Match_Confidence；置信度低于 [MATCH_THRESHOLD]
 * 时标记 [DiscoverResultItem.isLowConfidence]，供界面展示手动纠正入口。
 */
@VisibleForTesting
internal fun WorkMatch.toResultItem(): DiscoverResultItem {
    val low = matchConfidence < MATCH_THRESHOLD
    val card = WorkCardUiModel(
        coverUrl = work.coverUrl,
        title = work.titles.canonical,
        subtitle = workSubtitle(work),
        type = work.mediaType.discoverLabel(),
        // 评分需经聚合用例单独获取，搜索结果不加载，保持「暂无数据」。
        ratingText = null,
        sourceTags = listOf(sourceTag.discoverLabel(), confidenceLabel(matchConfidence)),
        completionCost = work.completionCost?.discoverLabel(),
    )
    return DiscoverResultItem(
        workId = work.id,
        card = card,
        isLowConfidence = low,
        sourceId = sourceTag,
        // 作品 id 去掉来源前缀就是该平台的条目编号（见 WorkIds）。
        sourceItemId = WorkIds.sourceItemId(work.id, sourceTag),
        matchConfidence = matchConfidence,
    )
}

/**
 * 由用户选中的候选构建手动纠正用的 [SourceRef]。
 *
 * 置 `userOverridden = true`，写入后后续同步不再自动改写该链接。
 */
@VisibleForTesting
internal fun DiscoverResultItem.toSourceRef(): SourceRef = SourceRef(
    sourceId = sourceId,
    sourceItemId = sourceItemId,
    matchConfidence = matchConfidence,
    userOverridden = true,
)

/** 把置信度格式化为可读的 Match_Confidence 文案。 */
@VisibleForTesting
internal fun confidenceLabel(confidence: Float): String = "匹配度 ${(confidence.coerceIn(0f, 1f) * 100).roundToInt()}%"

/** 拼接副标题：日文 / 罗马音 / 英文名（取其一）+ 年份。 */
private fun workSubtitle(work: Work): String {
    val altTitle = work.titles.ja ?: work.titles.romaji ?: work.titles.en
    return listOfNotNull(altTitle, work.year?.toString()).joinToString(separator = " · ")
}

/** 媒介类型展示文案。 */
private fun MediaType.discoverLabel(): String = when (this) {
    MediaType.ANIME -> "动画"
    MediaType.MANGA -> "漫画"
    MediaType.NOVEL -> "小说"
    MediaType.GAME -> "游戏"
    MediaType.VN -> "视觉小说"
    MediaType.OTHER -> "其他"
}

/** 补完成本展示文案。 */
private fun CompletionCost.discoverLabel(): String = when (this) {
    CompletionCost.TONIGHT -> "今晚"
    CompletionCost.WEEKEND -> "周末"
    CompletionCost.LONG_HAUL -> "长期坑"
}

/** 数据源展示文案（来源标签）。 */
internal fun SourceId.discoverLabel(): String = when (this) {
    SourceId.BANGUMI -> "Bangumi"
    SourceId.ANILIST -> "AniList"
    SourceId.TENRAI -> "Tenrai"
    SourceId.JIKAN -> "Jikan"
    SourceId.MAL -> "MAL"
    SourceId.VNDB -> "VNDB"
}

// region 跨源合并

/** 收集一个作品的全部可比较标题变体（复用 domain 的通用实现）。 */
private fun Work.allTitles(): List<String> = allTitleVariants()

/**
 * 跨源合并搜索结果：复用领域层通用聚类 [clusterMatches] 和 [representativeOf]
 * （与仓库持久化 source links 使用同一算法，保证卡片 workId == 详情聚合的代表 id）。
 * 单源候选保留为 [DiscoverResultItem.mergeCandidates] 供手动纠正/拆分。纯函数。
 */
internal fun mergeCrossSource(matches: List<WorkMatch>): List<DiscoverResultItem> {
    if (matches.isEmpty()) return emptyList()
    return clusterMatches(matches).map { it.toMergedItem() }
}

/** 把一个同一作品的多源候选簇折叠为一张合并卡。 */
private fun List<WorkMatch>.toMergedItem(): DiscoverResultItem {
    val rep = representativeOf(this)
    val byConfidence = sortedByDescending { it.matchConfidence }
    val bestConfidence = byConfidence.first().matchConfidence
    val sourceLabels = map { it.sourceTag }.distinct().map { it.discoverLabel() }
    // 多源合并视为高置信（已聚合 ≥2 源）；仅单源且低于阈值才标低置信，触发手动纠正。
    val low = sourceLabels.size <= 1 && bestConfidence < MATCH_THRESHOLD.toFloat()
    val tags = sourceLabels + confidenceLabel(bestConfidence)
    val card = WorkCardUiModel(
        coverUrl = rep.work.coverUrl ?: byConfidence.firstNotNullOfOrNull { it.work.coverUrl },
        title = rep.work.displayTitle(),
        subtitle = workSubtitle(rep.work),
        type = rep.work.mediaType.discoverLabel(),
        ratingText = null,
        sourceTags = tags,
        completionCost = rep.work.completionCost?.discoverLabel(),
    )
    return DiscoverResultItem(
        workId = rep.work.id,
        card = card,
        isLowConfidence = low,
        sourceId = rep.sourceTag,
        sourceItemId = WorkIds.sourceItemId(rep.work.id, rep.sourceTag),
        matchConfidence = bestConfidence,
        mergeCandidates = if (size > 1) map { it.toResultItem() } else emptyList(),
    )
}

// endregion

// endregion
