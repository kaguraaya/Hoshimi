package com.acgcompass.feature.report

import android.app.Application
import android.graphics.RectF
import com.acgcompass.domain.report.ReportPeriod
import com.acgcompass.domain.report.ReportWork
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReportLayoutTest {
    @Test
    fun pyramidRowsGrowThenUseTailWidth() {
        assertEquals(listOf(1), ReportPyramid.rowSizes(1, 5))
        assertEquals(listOf(1, 2, 1), ReportPyramid.rowSizes(4, 5))
        assertEquals(listOf(1, 2, 3, 5, 3), ReportPyramid.rowSizes(14, 5))
        assertEquals(40, ReportPyramid.rowSizes(40, 8).sum())
    }

    @Test
    fun timelineListsEveryMonthOfPeriodInOrder() {
        val works = listOf(work("a", 11), work("b", 10), work("c", 11))
        val months = ReportTimeline.months(ReportPeriod(2026, 4), works, ZoneOffset.UTC)
        assertEquals(listOf(10, 11, 12), months.map { it.first })
        assertEquals(listOf("a", "c"), months[1].second.map { it.workId })
        assertTrue(months[2].second.isEmpty())
        assertEquals(12, ReportTimeline.months(ReportPeriod(2026), works, ZoneOffset.UTC).size)
    }

    @Test
    fun polaroidPutsFavoriteNearCenterAndIsStable() {
        val area = RectF(0f, 0f, 900f, 1200f)
        val ids = (1..9).map { "w$it" }
        val cards = ReportPolaroid.layout(ids, area)
        assertEquals(9, cards.size)
        val distance = cards.map { hypot(it.x - area.centerX(), it.y - area.centerY()) }
        assertTrue(distance[0] == distance.min())
        assertTrue(cards[0].width > cards[8].width)
        assertTrue(cards.all { kotlin.math.abs(it.angle) <= 7f })
        assertEquals(cards, ReportPolaroid.layout(ids, area))
        assertEquals(9, ReportPolaroid.layout((1..20).map { "w$it" }, area).size)
    }

    private fun work(id: String, month: Int) = ReportWork(
        workId = id,
        title = id,
        coverUrl = null,
        rating = 8,
        community = 7.0,
        year = 2026,
        episodes = 12,
        minutesPerEpisode = null,
        finishedAt = LocalDate.of(2026, month, 10).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        genres = emptyList(),
    )
}
