@file:Suppress("MagicNumber") // 分享图的画布坐标和字号

package com.acgcompass.feature.report

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.acgcompass.domain.report.ReportPeriod
import com.acgcompass.domain.report.ReportWork
import java.time.Instant
import java.time.ZoneId

/**
 * 时间线：时间范围里的每个月一行，左边月份和时间轴，右边当月看完的封面横排（评分高的在前）；
 * 当月最高分那张大一档。一行放不下时缩小其余封面，再放不下就在行尾写「+N」。
 */
internal object ReportTimeline {
    private const val ROW_GAP = 12f
    private const val LABEL_WIDTH = 132f
    private const val AXIS_OFFSET = 112f
    private const val COVER_GAP = 10f
    private const val SMALL_SHARE = 0.76f
    private const val MIN_SMALL_SHARE = 0.5f
    private const val MORE_WIDTH = 84f

    /** 把作品按看完的月份分组：返回时间范围里的每个月（月份数字）和那个月看完的作品，顺序保持输入顺序。 */
    fun months(
        period: ReportPeriod,
        works: List<ReportWork>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Pair<Int, List<ReportWork>>> {
        val first = period.startDate().monthValue
        val count = if (period.isYear) 12 else ReportPeriod.MONTHS_PER_SEASON
        val byMonth = works.groupBy { Instant.ofEpochMilli(it.finishedAt).atZone(zone).monthValue }
        return (first until first + count).map { month -> month to byMonth[month].orEmpty() }
    }

    fun draw(canvas: Canvas, scene: ReportScene) {
        val area = scene.frame.body
        val months = months(scene.report.period, scene.works)
        val rowH = (area.height() - ROW_GAP * (months.size - 1)) / months.size
        val maxCover = rowH.coerceAtMost(area.width() * 0.32f)
        val axisX = area.left + AXIS_OFFSET
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ReportDraw.PLACEHOLDER
            strokeWidth = 4f
        }
        canvas.drawLine(axisX, area.top + rowH / 2, axisX, area.bottom - rowH / 2, axisPaint)
        months.forEachIndexed { i, (month, works) ->
            val top = area.top + i * (rowH + ROW_GAP)
            val row = RectF(area.left + LABEL_WIDTH, top, area.right, top + rowH)
            drawMonth(canvas, axisX, row, month, works.isNotEmpty())
            drawCovers(canvas, scene, row, maxCover, works)
        }
    }

    private fun drawMonth(canvas: Canvas, axisX: Float, row: RectF, month: Int, active: Boolean) {
        val inks = ReportInks.LIGHT
        val size = (row.height() * 0.42f).coerceIn(24f, 40f)
        val paint = ReportDraw.text(if (active) inks.ink else inks.muted, size, bold = active)
        canvas.drawText("$month 月", row.left - LABEL_WIDTH, row.centerY() + size * 0.36f, paint)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (active) inks.accent else ReportDraw.PLACEHOLDER }
        canvas.drawCircle(axisX, row.centerY(), if (active) 10f else 7f, dot)
    }

    private fun drawCovers(canvas: Canvas, scene: ReportScene, row: RectF, maxCover: Float, works: List<ReportWork>) {
        if (works.isEmpty()) {
            val paint = ReportDraw.text(ReportInks.LIGHT.muted, (row.height() * 0.3f).coerceIn(22f, 30f))
            canvas.drawText("这个月没有看完的", row.left, row.centerY() + paint.textSize * 0.36f, paint)
            return
        }
        val bigH = maxCover
        val bigW = bigH / ReportDraw.COVER_RATIO
        val rest = works.size - 1
        val room = row.width() - bigW - COVER_GAP
        val share = if (rest == 0) {
            SMALL_SHARE
        } else {
            ((room - COVER_GAP * rest) / rest / bigW).coerceIn(MIN_SMALL_SHARE, SMALL_SHARE)
        }
        val smallW = bigW * share
        val fits = if (rest == 0) 0 else fitCount(room, smallW, rest)
        val bottom = row.centerY() + bigH / 2
        ReportDraw.drawCover(
            canvas,
            RectF(row.left, bottom - bigH, row.left + bigW, bottom),
            scene.covers[works[0].workId],
            12f,
        )
        var left = row.left + bigW + COVER_GAP
        works.drop(1).take(fits).forEach { work ->
            val rect = RectF(left, bottom - smallW * ReportDraw.COVER_RATIO, left + smallW, bottom)
            ReportDraw.drawCover(canvas, rect, scene.covers[work.workId], 10f)
            left += smallW + COVER_GAP
        }
        if (fits < rest) {
            val paint = ReportDraw.text(ReportInks.LIGHT.accent, (bigH * 0.28f).coerceIn(22f, 36f), bold = true)
            canvas.drawText("+${rest - fits}", left + 4f, row.centerY() + paint.textSize * 0.36f, paint)
        }
    }

    /** 小封面一行能放几张；放不下全部时给「+N」留位置。 */
    private fun fitCount(room: Float, smallW: Float, rest: Int): Int {
        val all = ((room + COVER_GAP) / (smallW + COVER_GAP)).toInt()
        return if (all >= rest) {
            rest
        } else {
            ((room - MORE_WIDTH + COVER_GAP) / (smallW + COVER_GAP)).toInt().coerceAtLeast(
                0,
            )
        }
    }
}
