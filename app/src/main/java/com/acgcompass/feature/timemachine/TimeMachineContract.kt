package com.acgcompass.feature.timemachine

import com.acgcompass.domain.model.ChangeLog
import com.acgcompass.domain.model.ChangeType
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** 「暂无数据」占位（缺失即标记、绝不伪造）。 */
internal const val NO_DATA: String = "暂无数据"

/**
 * 时间线单条变更的展示模型。
 *
 * @property workTitle 作品标题（缺失回退「暂无数据」）。
 * @property description 变更一句话描述（状态 / 评分 / 短评 / 进度 / 新增）。
 * @property changedAt 变更时间戳。
 */
data class TimelineEntryUi(
    val workTitle: String,
    val description: String,
    val changedAt: Long,
    /** 关联作品 id；非空时该条目可点击跳转详情。基线「初始快照」等无对应作品的条目为 null。 */
    val workId: String? = null,
)

/**
 * 按月分组的时间线分段。
 *
 * @property label 分段标签（如「2024 年 3 月」）。
 * @property entries 该月的变更条目（按时间倒序）。
 */
data class TimelinePeriodUi(
    val label: String,
    val entries: List<TimelineEntryUi>,
)

/**
 * 月度 / 年度报告摘要。仅基于本地变更日志中**真实存在**的数据统计，缺失维度
 * 显示「暂无数据」，绝不伪造。
 *
 * @property totalChanges 变更总数。
 * @property addedCount 新增收藏数。
 * @property statusChanges 状态变化数。
 * @property ratingChanges 评分变化数。
 * @property reviewChanges 短评变化数。
 * @property progressChanges 进度变化数。
 * @property avgNewRatingText 本期新评分均值文案；无评分变化时为「暂无数据」。
 * @property maxNewRatingText 本期最高新评分文案；无评分变化时为「暂无数据」。
 * @property ratingFlipflops 「评分打脸现场」：同一作品评分明显变化。
 * @property pastThoughts 「当时的短评」：历史短评回顾。
 */
data class TimeMachineReportUi(
    val totalChanges: Int,
    val addedCount: Int,
    val statusChanges: Int,
    val ratingChanges: Int,
    val reviewChanges: Int,
    val progressChanges: Int,
    val avgNewRatingText: String,
    val maxNewRatingText: String,
    val ratingFlipflops: List<String>,
    val pastThoughts: List<String>,
)

/**
 * 时光机顶部概览卡，汇总收藏数、最后同步时间、近期变化数和本期评分均值。
 *
 * @property collectionCount 当前本地收藏数（user_collections）。
 * @property lastSyncText 最后同步时间文案（来自 SyncStatusRepository，未同步显示「尚未同步」）。
 * @property recentChangeCount 近期变化数（变更日志总数）。
 * @property avgRatingText 本期评分均值文案；无评分时为「暂无数据」。
 */
data class TimeMachineOverviewUi(
    val collectionCount: Int,
    val lastSyncText: String,
    val recentChangeCount: Int,
    val avgRatingText: String,
)

/**
 * 基线快照中可浏览的单部作品。
 *
 * @property workId 作品 id（列表 key）。
 * @property title 作品标题（缺失回退「暂无数据」）。
 * @property typeLabel 媒介类型中文（动画 / 漫画 / 小说 / 游戏 / 视觉小说 / 暂无数据）。
 * @property statusLabel 收藏状态（想看 / 在看 / 看过 / 搁置 / 抛弃 / 暂无数据）。
 * @property ratingText 我的评分文案；未评为「未评分」。
 * @property progressText 进度文案；缺失为「暂无进度」。
 * @property syncTimeText 同步 / 记录时间文案。
 */
data class BaselineWorkUi(
    val workId: String,
    val title: String,
    val typeLabel: String,
    val statusLabel: String,
    val ratingText: String,
    val progressText: String,
    val syncTimeText: String,
)

/**
 * 按收藏状态分组的基线快照，可在界面折叠展开。
 *
 * @property statusLabel 分组状态标签。
 * @property works 该状态下的作品（按标题排序）。
 */
data class BaselineGroupUi(
    val statusLabel: String,
    val works: List<BaselineWorkUi>,
)

/** 时光机页整体 UI 状态（概览 + 基线作品 + 时间线 + 报告）。 */
data class TimeMachineUiState(
    val timeline: List<TimelinePeriodUi>,
    val report: TimeMachineReportUi,
    val overview: TimeMachineOverviewUi? = null,
    val baselineGroups: List<BaselineGroupUi> = emptyList(),
    /** 基线提示文案（如「导入了 N 部收藏」）；无基线时为 null。 */
    val baselineHeadline: String? = null,
)

/** 评分「打脸」阈值：新旧评分差 `>=` 此值视为明显变化。 */
private const val RATING_FLIP_THRESHOLD: Int = 2

/**
 * 由变更日志构建时光机页 UI 状态（纯函数）。
 *
 * @param changeLogs 全部变更日志（顺序不限，内部按时间排序）。
 * @param titlesById workId → 作品标题映射；缺失时条目标题回退「暂无数据」。
 * @param zoneId 分组所用时区（默认系统时区）。
 */
fun buildTimeMachineUiState(
    changeLogs: List<ChangeLog>,
    titlesById: Map<String, String>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): TimeMachineUiState {
    fun title(workId: String): String = titlesById[workId]?.takeIf { it.isNotBlank() } ?: NO_DATA

    // --- 时间线：按 年-月 倒序分组 ---
    val sorted = changeLogs.sortedByDescending { it.changedAt }
    val periods = sorted
        .groupBy {
            val date = Instant.ofEpochMilli(it.changedAt).atZone(zoneId).toLocalDate()
            date.year to date.monthValue
        }
        .map { (ym, logs) ->
            TimelinePeriodUi(
                label = "${ym.first} 年 ${ym.second} 月",
                entries = logs.map { log ->
                    TimelineEntryUi(
                        workTitle = title(log.workId),
                        description = log.describe(),
                        changedAt = log.changedAt,
                        workId = log.workId,
                    )
                },
            )
        }

    // --- 报告统计（仅基于真实变更，不伪造）---
    val byType = changeLogs.groupingBy { it.changeType }.eachCount()
    val ratingNewValues = changeLogs
        .filter { it.changeType == ChangeType.RATING }
        .mapNotNull { it.newValue?.toIntOrNull() }
    val avgText = if (ratingNewValues.isEmpty()) {
        NO_DATA
    } else {
        "%.1f 分".format(ratingNewValues.average())
    }
    val maxText = ratingNewValues.maxOrNull()?.let { "$it 分" } ?: NO_DATA

    val flipflops = changeLogs
        .filter { it.changeType == ChangeType.RATING }
        .mapNotNull { log ->
            val old = log.oldValue?.toIntOrNull()
            val new = log.newValue?.toIntOrNull()
            if (old != null && new != null && abs(new - old) >= RATING_FLIP_THRESHOLD) {
                "《${title(log.workId)}》：$old → $new 分"
            } else {
                null
            }
        }

    val pastThoughts = changeLogs
        .filter { it.changeType == ChangeType.REVIEW && !it.oldValue.isNullOrBlank() }
        .map { "《${title(it.workId)}》当时：${it.oldValue}" }

    val report = TimeMachineReportUi(
        totalChanges = changeLogs.size,
        addedCount = byType[ChangeType.ADDED] ?: 0,
        statusChanges = byType[ChangeType.STATUS] ?: 0,
        ratingChanges = byType[ChangeType.RATING] ?: 0,
        reviewChanges = byType[ChangeType.REVIEW] ?: 0,
        progressChanges = byType[ChangeType.PROGRESS] ?: 0,
        avgNewRatingText = avgText,
        maxNewRatingText = maxText,
        ratingFlipflops = flipflops,
        pastThoughts = pastThoughts,
    )

    return TimeMachineUiState(timeline = periods, report = report)
}

/** 变更 → 一句话描述。 */
private fun ChangeLog.describe(): String = when (changeType) {
    ChangeType.ADDED -> "新增到收藏${newValue?.let { "：$it" } ?: ""}"
    ChangeType.STATUS -> "状态：${oldValue ?: NO_DATA} → ${newValue ?: NO_DATA}"
    ChangeType.RATING -> "评分：${oldValue ?: "—"} → ${newValue ?: NO_DATA}"
    // 短评变化直接展示当时写下的内容（截断），而非笼统「有更新」。
    ChangeType.REVIEW -> newValue?.takeIf { it.isNotBlank() }?.let { "短评：$it" } ?: "短评有更新"
    ChangeType.PROGRESS -> "进度：${oldValue ?: "—"} → ${newValue ?: NO_DATA}"
    ChangeType.UNKNOWN -> "有变化"
}

/** 状态分组展示顺序：想看、在看、看过、搁置、抛弃，其余追加在后。 */
private val STATUS_ORDER: List<String> = listOf("想看", "在看", "看过", "搁置", "抛弃")

private val SYNC_TIME_FORMATTER: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .withZone(ZoneId.systemDefault())

/**
 * 构建基线浏览所需的单部作品原始输入。
 * 由 ViewModel 用 user_collections + 作品标题/类型解析后传入，contract 负责格式化与分组。
 */
data class BaselineItem(
    val workId: String,
    val title: String,
    val typeLabel: String,
    val status: String?,
    val rating: Int?,
    val progress: Int?,
    val syncedAt: Long,
)

/**
 * 把 [items] 按收藏状态分组为可浏览的基线视图；缺失维度使用占位值，
 * 绝不伪造。状态分组按 [STATUS_ORDER] 排序，组内按标题排序。
 */
fun buildBaselineGroups(items: List<BaselineItem>): List<BaselineGroupUi> {
    if (items.isEmpty()) return emptyList()
    return items
        .groupBy { it.status?.takeIf { s -> s.isNotBlank() } ?: NO_DATA }
        .map { (status, group) ->
            BaselineGroupUi(
                statusLabel = status,
                works = group
                    .sortedBy { it.title }
                    .map { item ->
                        BaselineWorkUi(
                            workId = item.workId,
                            title = item.title.takeIf { it.isNotBlank() } ?: NO_DATA,
                            typeLabel = item.typeLabel,
                            statusLabel = status,
                            ratingText = item.rating?.let { "$it 分" } ?: "未评分",
                            progressText = item.progress?.let { "进度 $it" } ?: "暂无进度",
                            syncTimeText = SYNC_TIME_FORMATTER.format(
                                java.time.Instant.ofEpochMilli(item.syncedAt),
                            ),
                        )
                    },
            )
        }
        .sortedBy { group ->
            val idx = STATUS_ORDER.indexOf(group.statusLabel)
            if (idx >= 0) idx else STATUS_ORDER.size
        }
}

/**
 * 概览卡数据；本期评分均值取当前收藏中已评分项的均值，无评分时使用占位值。
 *
 * @param collectionCount 当前收藏数。
 * @param lastSyncText 最后同步时间文案。
 * @param recentChangeCount 近期变化数。
 * @param ratings 当前收藏中的有效评分列表。
 */
fun buildOverview(
    collectionCount: Int,
    lastSyncText: String,
    recentChangeCount: Int,
    ratings: List<Int>,
): TimeMachineOverviewUi = TimeMachineOverviewUi(
    collectionCount = collectionCount,
    lastSyncText = lastSyncText,
    recentChangeCount = recentChangeCount,
    avgRatingText = if (ratings.isEmpty()) NO_DATA else "%.1f 分".format(ratings.average()),
)

/**
 * 历史收藏时间线条目原始输入——把每条用户收藏（含状态/评分/短评/时间）渲染成时间线。
 *
 * @property timestamp 事件时间（优先 Bangumi 源更新时间，回退本地同步时间）。
 */
data class CollectionTimelineInput(
    val workId: String,
    val title: String,
    val status: String?,
    val rating: Int?,
    val comment: String?,
    val progress: Int?,
    val timestamp: Long,
)

/**
 * 由历史收藏构建「过去看的番」时间线（按月倒序分组）。每条展示当时状态 / 评分 / 短评 / 进度。
 * 纯函数；缺失字段省略而非伪造。
 */
fun buildCollectionTimeline(
    items: List<CollectionTimelineInput>,
    zoneId: ZoneId = ZoneId.systemDefault(),
): List<TimelinePeriodUi> {
    if (items.isEmpty()) return emptyList()
    return items
        .sortedByDescending { it.timestamp }
        .groupBy {
            val date = Instant.ofEpochMilli(it.timestamp).atZone(zoneId).toLocalDate()
            date.year to date.monthValue
        }
        .map { (ym, group) ->
            TimelinePeriodUi(
                label = "${ym.first} 年 ${ym.second} 月",
                entries = group.map { it.toTimelineEntry() },
            )
        }
}

private fun CollectionTimelineInput.toTimelineEntry(): TimelineEntryUi {
    val parts = buildList {
        status?.takeIf { it.isNotBlank() }?.let { add(it) }
        rating?.let { add("评分 $it") }
        progress?.takeIf { it > 0 }?.let { add("进度 $it") }
        comment?.takeIf { it.isNotBlank() }?.let { add("短评：$it") }
    }
    return TimelineEntryUi(
        workTitle = title.takeIf { it.isNotBlank() } ?: NO_DATA,
        description = parts.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "已收藏",
        changedAt = timestamp,
        workId = workId,
    )
}
