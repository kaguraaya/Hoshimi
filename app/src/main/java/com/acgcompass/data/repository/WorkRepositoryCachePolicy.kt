package com.acgcompass.data.repository

import com.acgcompass.data.datastore.SettingsState
import com.acgcompass.domain.cache.CacheLimitMode
import com.acgcompass.domain.cache.CachePolicy
import com.acgcompass.domain.model.Work

/** 公共缓存补充用到的几个纯函数：设置 → 缓存策略、目标条数、单条资料大小估算、本季开播日期范围。 */

private const val BYTES_PER_MB = 1024L * 1024L

/** 估算一部公共缓存作品占用的空间（按空间上限管理时换算成条数）。 */
internal const val ESTIMATED_PUBLIC_WORK_SLOT_BYTES = 64L * 1024L

/** 每条作品资料除文本外的固定开销估计。 */
private const val METADATA_BASE_BYTES = 512L
private const val MONTHS_PER_QUARTER = 3

internal fun SettingsState.toCachePolicy(): CachePolicy = CachePolicy(
    mode = cacheLimitMode,
    countLimit = autoCacheMaxCount,
    spaceLimitBytes = cacheSpaceLimitMb.toLong() * BYTES_PER_MB,
    backgroundFillEnabled = cacheBackgroundFillEnabled,
)

internal fun CachePolicy.targetWorkSlots(): Int = when (mode) {
    CacheLimitMode.COUNT -> normalizedCountLimit
    CacheLimitMode.SPACE -> (normalizedSpaceLimitBytes / ESTIMATED_PUBLIC_WORK_SLOT_BYTES)
        .toInt()
        .coerceIn(CachePolicy.MIN_COUNT_LIMIT, CachePolicy.MAX_COUNT_LIMIT)
}

internal fun estimateMetadataBytes(work: Work): Long = METADATA_BASE_BYTES +
    listOfNotNull(
        work.titles.canonical,
        work.titles.ja,
        work.titles.romaji,
        work.titles.en,
        work.summary,
        work.coverUrl,
    ).sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } +
    work.titles.aliases.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() }

/** 当前季度的开播日期范围过滤，用于本季榜单 `sort=rank` 浏览。 */
internal fun currentSeasonAirDateRange(today: java.time.LocalDate = java.time.LocalDate.now()): List<String> {
    val startMonth = ((today.monthValue - 1) / MONTHS_PER_QUARTER) * MONTHS_PER_QUARTER + 1
    val start = java.time.LocalDate.of(today.year, startMonth, 1)
    val end = start.plusMonths(MONTHS_PER_QUARTER.toLong())
    val fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE
    return listOf(">=${start.format(fmt)}", "<${end.format(fmt)}")
}
