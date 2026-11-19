package com.acgcompass.domain.report

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchReportBuilderTest {
    @Suppress("LongParameterList")
    private fun work(
        id: String,
        rating: Int?,
        community: Double?,
        year: Int = 2026,
        eps: Int = 12,
        genres: List<String> = emptyList(),
    ) = ReportWork(id, "作品$id", null, rating, community, year, eps, null, 0L, genres)

    @Test
    fun periodsAlignWithAnimeSeasons() {
        val autumn = ReportPeriod.seasonOf(LocalDate.of(2026, 11, 19))
        assertEquals(ReportPeriod(2026, 4), autumn)
        assertEquals("2026 年秋季", autumn.label)
        assertEquals("10–12 月", autumn.range)
        val choices = ReportPeriod.choices(LocalDate.of(2026, 2, 1))
        assertEquals(
            listOf(ReportPeriod(2026, 1), ReportPeriod(2025, 4), ReportPeriod(2026), ReportPeriod(2025)),
            choices,
        )
        val zone = ZoneId.of("Asia/Shanghai")
        val oct1 = LocalDate.of(2026, 10, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val sep30 = LocalDate.of(2026, 9, 30).atStartOfDay(zone).toInstant().toEpochMilli()
        assertTrue(autumn.contains(oct1, zone))
        assertFalse(autumn.contains(sep30, zone))
    }

    @Test
    fun buildsStatistics() {
        val works = listOf(
            work("a", 9, 7.5, genres = listOf("日常", "百合")),
            work("b", 6, 8.2, year = 2010, eps = 24, genres = listOf("日常", "科幻")),
            work("c", null, 7.0, eps = 1, genres = listOf("日常")),
            work("d", 8, 7.9),
        )
        val report = WatchReportBuilder.build(ReportPeriod(2026, 4), works, dropped = 2)
        assertEquals(listOf("a", "d", "b", "c"), report.finished.map { it.workId })
        assertEquals(49, report.episodes)
        // (12 + 24 + 1 + 12) × 24 分钟 = 1176 分钟 ≈ 20 小时
        assertEquals(20, report.hours)
        assertEquals(23.0 / 3, report.averageRating!!, 1e-9)
        assertEquals("a", report.favorite?.workId)
        assertEquals("日常", report.topGenres.first())
        assertEquals(0.75f, report.newShare)
        assertEquals("a", report.aboveCrowd?.workId)
        assertEquals("b", report.belowCrowd?.workId)
        assertEquals(2, report.dropped)
        assertTrue(report.enoughForShare)
    }

    @Test
    fun smallReportIsNotShared() {
        val report = WatchReportBuilder.build(ReportPeriod(2026), listOf(work("a", 8, 8.0)), dropped = 0)
        assertFalse(report.enoughForShare)
        assertEquals(null, report.aboveCrowd)
    }
}
