package com.acgcompass.feature.backlog

import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Priority
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.displayTitle
import com.acgcompass.domain.repository.BacklogSort
import com.acgcompass.domain.repository.BulkOp

/**
 * 待补池单条展示数据：把领域 [BacklogItem] 与其关联 [Work] 折叠为一条 UI 条目。
 *
 * [work] 可能为 `null`（作品数据尚未本地化），此时卡片以「暂无数据」兜底渲染，不伪造内容
 * 。
 */
data class BacklogCardItem(
    val item: BacklogItem,
    val work: Work?,
) {
    val workId: String get() = item.workId
}

/**
 * 一键抽番结果的 UI 展示模型。携带**作品名** [title]（而非只有 [workId]），
 * 修复弹窗只显示作品 id 数字的问题；作品尚未本地化时 [title] 为 `null`，回退展示 [workId]。
 */
data class DrawUiResult(
    val workId: String?,
    val title: String?,
    val reason: String,
)

/** 待补池排序的可读标签。 */
fun BacklogSort.label(): String = when (this) {
    BacklogSort.ADDED_DESC -> "最近加入"
    BacklogSort.ADDED_ASC -> "最早加入"
    BacklogSort.PRIORITY_DESC -> "优先级"
    BacklogSort.DUST_DAYS_DESC -> "吃灰最久"
}

/** 优先级可读标签。 */
fun Priority.label(): String = when (this) {
    Priority.HIGH -> "高"
    Priority.MEDIUM -> "中"
    Priority.LOW -> "低"
}

/** 批量操作可读标签。 */
fun BulkOp.label(): String = when (this) {
    BulkOp.DELETE -> "删除"
    BulkOp.ARCHIVE_TO_DUST_MUSEUM -> "移入吃灰馆"
    BulkOp.RESTORE_FROM_DUST_MUSEUM -> "移出吃灰馆"
    BulkOp.SET_PRIORITY_HIGH -> "设为高"
    BulkOp.SET_PRIORITY_MEDIUM -> "设为中"
    BulkOp.SET_PRIORITY_LOW -> "设为低"
}

/** 媒介类型可读标签。 */
fun MediaType.label(): String = when (this) {
    MediaType.ANIME -> "动画"
    MediaType.MANGA -> "漫画"
    MediaType.NOVEL -> "小说"
    MediaType.GAME -> "游戏"
    MediaType.VN -> "视觉小说"
    MediaType.OTHER -> "其他"
}

/** 数据源来源标签。 */
fun SourceId.sourceLabel(): String = when (this) {
    SourceId.BANGUMI -> "Bangumi"
    SourceId.ANILIST -> "AniList"
    SourceId.TENRAI -> "Tenrai"
    SourceId.JIKAN -> "Jikan"
    SourceId.MAL -> "MAL"
    SourceId.VNDB -> "VNDB"
}

/** 补完成本分桶可读标签。 */
private fun CompletionCost.label(): String = when (this) {
    CompletionCost.TONIGHT -> "今晚可看完"
    CompletionCost.WEEKEND -> "周末"
    CompletionCost.LONG_HAUL -> "长期坑"
}

/**
 * 把待补条目折叠为统一作品卡片模型 [WorkCardUiModel]。
 *
 * 缺失即标记：作品缺失时标题回退为作品 id；评分聚合尚未接入时显示「暂无数据」。
 * 待补状态展示优先级，吃灰天数 > 0 时追加吃灰提示。
 */
fun BacklogCardItem.toWorkCardUiModel(): WorkCardUiModel {
    val w = work
    val subtitle = buildString {
        val aliases = w?.titles?.aliases?.firstOrNull()
        if (!aliases.isNullOrBlank()) append(aliases)
        val year = w?.year
        if (year != null) {
            if (isNotEmpty()) append(" · ")
            append(year.toString())
        }
    }
    val backlogStatus = buildString {
        append("${item.priority.label()}优先级")
        if (item.dustDays > 0) append(" · 吃灰 ${item.dustDays} 天")
    }
    return WorkCardUiModel(
        coverUrl = w?.coverUrl,
        title = w?.displayTitle() ?: item.workId,
        subtitle = subtitle,
        type = w?.mediaType?.label() ?: "暂无数据",
        // 评分聚合尚未接入；此处保持 null，由卡片显示「暂无数据」。
        ratingText = null,
        sourceTags = listOfNotNull(w?.primarySource?.sourceLabel()),
        backlogStatus = backlogStatus,
        completionCost = w?.completionCost?.label(),
        moodRiskTags = item.moodTags + item.riskTags,
    )
}
