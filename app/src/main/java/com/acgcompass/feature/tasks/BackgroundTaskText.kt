package com.acgcompass.feature.tasks

import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.model.BackgroundTaskStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 任务在页面上的名字。 */
internal fun BackgroundTaskKind.label(): String = when (this) {
    BackgroundTaskKind.SYNC -> "同步 Bangumi 收藏"
    BackgroundTaskKind.OUTBOX -> "把修改推到 Bangumi"
    BackgroundTaskKind.FEATURE_ENRICHMENT -> "补作品资料"
    BackgroundTaskKind.RATING_BACKFILL -> "补其它平台评分"
    BackgroundTaskKind.CACHE_FILL -> "补充公共缓存"
    BackgroundTaskKind.POOL_EXPAND -> "扩充作品库"
    BackgroundTaskKind.AUTO_BACKUP -> "自动备份"
    BackgroundTaskKind.DETAIL_EXTRAS -> "详情页评论与角色"
    BackgroundTaskKind.DETAIL_RATINGS -> "详情页各平台评分"
}

/** 任务做什么的一句话。 */
internal fun BackgroundTaskKind.description(): String = when (this) {
    BackgroundTaskKind.SYNC -> "按设置的间隔从 Bangumi 拉取收藏和评分"
    BackgroundTaskKind.OUTBOX -> "在应用里改的状态、评分，联网后推到 Bangumi"
    BackgroundTaskKind.FEATURE_ENRICHMENT -> "给本地作品补标签、评分人数、Staff 等，口味匹配度要用"
    BackgroundTaskKind.RATING_BACKFILL -> "给只有一个平台评分的动画去 AniList、Tenrai 等平台找评分"
    BackgroundTaskKind.CACHE_FILL -> "使用应用时按各数据源限速慢慢补充公开资料，和「缓存管理」页显示的是同一份记录"
    BackgroundTaskKind.POOL_EXPAND -> "在「为你排行」里点「补充作品库」时运行"
    BackgroundTaskKind.AUTO_BACKUP -> "每周一次，写完读回检查"
    BackgroundTaskKind.DETAIL_EXTRAS -> "打开详情页时拉评论摘要和角色 · Staff，这里记最近一次的结果"
    BackgroundTaskKind.DETAIL_RATINGS ->
        "打开详情页时刷新各平台评分，这里记最近一次哪个平台没拿到；某个平台额度快用完且本地已有它的评分时，先用本地的"
}

/** 状态标签的种类，决定颜色。 */
internal enum class TaskBadge { RUNNING, OK, FAILED, DISABLED, IDLE }

internal fun BackgroundTaskStatus.badge(): TaskBadge = when {
    running -> TaskBadge.RUNNING
    failedLast() -> TaskBadge.FAILED
    disabled -> TaskBadge.DISABLED
    lastSuccessAt != null -> TaskBadge.OK
    else -> TaskBadge.IDLE
}

internal fun TaskBadge.text(): String = when (this) {
    TaskBadge.RUNNING -> "运行中"
    TaskBadge.OK -> "正常"
    TaskBadge.FAILED -> "上次失败"
    TaskBadge.DISABLED -> "已关闭"
    TaskBadge.IDLE -> "还没运行过"
}

/** 最近一次结果是失败（失败时间晚于成功时间）。 */
internal fun BackgroundTaskStatus.failedLast(): Boolean {
    errorCurrent?.let { return it }
    val failure = lastFailureAt
    return when {
        failure != null -> failure > (lastSuccessAt ?: 0L)
        else -> lastError != null && lastSuccessAt == null
    }
}

/** 最近一次开始之后既没成功也没失败、现在也不在运行：被系统中止或进程被回收的那次运行的开始时间。 */
internal fun BackgroundTaskStatus.unfinishedStart(): Long? {
    val started = lastStartedAt ?: return null
    val finished = maxOf(lastSuccessAt ?: 0L, lastFailureAt ?: 0L)
    return started.takeIf { !running && it > finished }
}

/** 卡片里的几行说明，按「上次成功 → 上次失败 → 结果 → 待处理 → 下次」排列。 */
internal fun BackgroundTaskStatus.detailLines(now: Long, zone: ZoneId = ZoneId.systemDefault()): List<String> =
    buildList {
        lastSuccessAt?.let { add("上次成功：${relativeTime(it, now, zone)}") }
        unfinishedStart()?.let { add("上次开始：${relativeTime(it, now, zone)}，没有记录到结束（可能被系统中止）") }
        if (failedLast()) {
            val at = lastFailureAt?.let { "${relativeTime(it, now, zone)}，" }.orEmpty()
            add("上次失败：$at${lastError ?: "原因不明"}")
        }
        lastSummary?.takeIf { it.isNotBlank() }?.let { add("结果：$it") }
        pendingNote?.let { add(it) }
        when {
            disabled -> add("设置里已关闭，不会按计划运行")
            running || nextRunAt == null -> Unit
            // 计划时间已到但还没开始：在等联网、电量等条件，满足后马上运行。
            nextRunAt <= now + MINUTE -> add("下次：条件满足后马上运行")
            else -> add("下次：${relativeTime(nextRunAt, now, zone)}")
        }
    }

/** 「刚刚」「1 分钟内」「12 分钟前」「3 小时后」「今天 14:05」「11月18日 09:30」。 */
internal fun relativeTime(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val diff = at - now
    val abs = kotlin.math.abs(diff)
    val suffix = if (diff < 0) "前" else "后"
    return when {
        abs < MINUTE -> if (diff <= 0) "刚刚" else "1 分钟内"
        abs < HOUR -> "${abs / MINUTE} 分钟$suffix"
        abs < SIX_HOURS -> "${abs / HOUR} 小时$suffix"
        else -> {
            val time = Instant.ofEpochMilli(at).atZone(zone)
            val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
            when (time.toLocalDate()) {
                today -> "今天 ${time.format(CLOCK)}"
                today.minusDays(1) -> "昨天 ${time.format(CLOCK)}"
                today.plusDays(1) -> "明天 ${time.format(CLOCK)}"
                else -> time.format(DATE_CLOCK)
            }
        }
    }
}

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val SIX_HOURS = 6 * HOUR
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日 HH:mm")
