package com.acgcompass.domain.report

import java.time.LocalDate
import java.time.ZoneId

/** 回顾的时间范围：动画季（1 / 4 / 7 / 10 月开始）或自然年。 */
data class ReportPeriod(val year: Int, val season: Int? = null) {
    val isYear: Boolean get() = season == null

    /** 「2026 年秋季」或「2026 年」。 */
    val label: String
        get() = season?.let { "$year 年${SEASON_NAMES[it - 1]}" } ?: "$year 年"

    /** 「10–12 月」或「全年」。 */
    val range: String
        get() = season?.let { "${(it - 1) * MONTHS_PER_SEASON + 1}–${it * MONTHS_PER_SEASON} 月" } ?: "全年"

    fun startDate(): LocalDate = LocalDate.of(year, season?.let { (it - 1) * MONTHS_PER_SEASON + 1 } ?: 1, 1)

    fun endDateExclusive(): LocalDate = if (isYear) {
        startDate().plusYears(
            1,
        )
    } else {
        startDate().plusMonths(MONTHS_PER_SEASON.toLong())
    }

    fun contains(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val date = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return !date.isBefore(startDate()) && date.isBefore(endDateExclusive())
    }

    companion object {
        const val MONTHS_PER_SEASON = 3
        val SEASON_NAMES = listOf("冬季", "春季", "夏季", "秋季")

        fun seasonOf(date: LocalDate): ReportPeriod = ReportPeriod(
            date.year,
            (date.monthValue - 1) / MONTHS_PER_SEASON + 1,
        )

        /** 页面上的四个选项：本季、上季、今年、去年。 */
        fun choices(today: LocalDate): List<ReportPeriod> {
            val current = seasonOf(today)
            val previous = seasonOf(today.minusMonths(MONTHS_PER_SEASON.toLong()))
            return listOf(current, previous, ReportPeriod(today.year), ReportPeriod(today.year - 1))
        }
    }
}

/** 这段时间看完的一部作品。 */
data class ReportWork(
    val workId: String,
    val title: String,
    val coverUrl: String?,
    /** 我的评分；没打分为 null。 */
    val rating: Int?,
    /** Bangumi 社区评分（10 分制）。 */
    val community: Double?,
    /** 放送年份。 */
    val year: Int?,
    val episodes: Int,
    /** 单集时长（分钟），不知道时为 null。 */
    val minutesPerEpisode: Int?,
    val finishedAt: Long,
    /** 社区标签里的题材（按标注人数从多到少）。 */
    val genres: List<String>,
)

/** 回顾的统计结果。 */
data class WatchReport(
    val period: ReportPeriod,
    /** 看完的作品，按我的评分从高到低（没打分的排后面）。 */
    val finished: List<ReportWork>,
    val episodes: Int,
    val hours: Int,
    val averageRating: Double?,
    val favorite: ReportWork?,
    val topGenres: List<String>,
    /** 当年 / 当季放送的作品占比（0–1）。 */
    val newShare: Float,
    /** 我给得比大众高最多、低最多的作品（差距不到 0.5 分不算）。 */
    val aboveCrowd: ReportWork?,
    val belowCrowd: ReportWork?,
    val dropped: Int,
) {
    /** 看完不到 [MIN_FINISHED] 部时不生成分享图。 */
    val enoughForShare: Boolean get() = finished.size >= MIN_FINISHED

    companion object {
        const val MIN_FINISHED = 3
    }
}

/** 由这段时间看完的作品算出回顾。纯函数。 */
object WatchReportBuilder {
    private const val DEFAULT_MINUTES = 24
    private const val MINUTES_PER_HOUR = 60
    private const val TOP_GENRES = 3
    private const val CROWD_GAP = 0.5

    fun build(period: ReportPeriod, finished: List<ReportWork>, dropped: Int): WatchReport {
        val sorted = finished.sortedWith(
            compareByDescending<ReportWork> { it.rating ?: -1 }.thenByDescending { it.community ?: 0.0 },
        )
        val minutes = finished.sumOf { it.episodes.coerceAtLeast(1) * (it.minutesPerEpisode ?: DEFAULT_MINUTES) }
        val rated = finished.mapNotNull { it.rating }
        val gaps = finished.mapNotNull { w -> w.rating?.let { r -> w.community?.let { c -> w to r - c } } }
        return WatchReport(
            period = period,
            finished = sorted,
            episodes = finished.sumOf { it.episodes },
            hours = (minutes + MINUTES_PER_HOUR / 2) / MINUTES_PER_HOUR,
            averageRating = rated.takeIf { it.isNotEmpty() }?.average(),
            favorite = sorted.firstOrNull { it.rating != null },
            topGenres = finished.flatMap { it.genres.take(TOP_GENRES) }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .take(TOP_GENRES).map { it.key },
            newShare = newShare(period, finished),
            aboveCrowd = gaps.filter { it.second >= CROWD_GAP }.maxByOrNull { it.second }?.first,
            belowCrowd = gaps.filter { it.second <= -CROWD_GAP }.minByOrNull { it.second }?.first,
            dropped = dropped,
        )
    }
}

private fun newShare(period: ReportPeriod, finished: List<ReportWork>): Float =
    if (finished.isEmpty()) 0f else finished.count { it.year == period.year } / finished.size.toFloat()

/** 回顾的数据来源。 */
interface WatchReportRepository {
    /** 这段时间看完的作品和弃番部数。看完时间优先用时光机记录的状态变化，其次是 Bangumi 收藏更新时间。 */
    suspend fun load(period: ReportPeriod): WatchReport
}
