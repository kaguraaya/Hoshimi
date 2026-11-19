@file:Suppress("MagicNumber") // 分享图的画布坐标和字号

package com.acgcompass.feature.report

import android.graphics.Canvas
import android.graphics.RectF

/**
 * 神作金字塔：第一行最高分一张大图（下面写片名），第二、三行两张、三张中图，再往下每行 k 张小图铺满。
 * k 从 4 到 8 里挑最小、整体能放进区域的；8 张也放不下时整体等比缩小，最多画 [MAX_WORKS] 部。
 */
internal object ReportPyramid {
    private const val GAP = 18f
    private const val HEAD_ROWS = 3
    private const val MIN_TAIL = 4
    private const val MAX_TAIL = 8
    private const val HEAD_SHRINK = 1.2f
    private const val LABEL_HEIGHT = 70f
    private const val RANKED = 6
    private const val MAX_WORKS = 38

    /** 每行放几张：1、2、3，其余每行 [tail] 张（最后一行可以不满）。 */
    fun rowSizes(count: Int, tail: Int): List<Int> {
        val sizes = mutableListOf<Int>()
        var left = count
        var row = 1
        while (left > 0) {
            val n = if (row <= HEAD_ROWS) row else tail
            sizes += minOf(n, left)
            left -= n
            row++
        }
        return sizes
    }

    private class Plan(val sizes: List<Int>, val widths: List<Float>, val heights: List<Float>) {
        val total: Float get() = heights.sum() + GAP * (heights.size - 1)
    }

    private fun plan(count: Int, area: RectF, tail: Int): Plan {
        val sizes = rowSizes(count, tail)
        val widths = sizes.indices.map { i ->
            if (i < HEAD_ROWS) area.width() / (i + 1 + HEAD_SHRINK) else (area.width() - GAP * (tail - 1)) / tail
        }
        val heights = widths.mapIndexed { i, w -> w * ReportDraw.COVER_RATIO + if (i == 0) LABEL_HEIGHT else 0f }
        return Plan(sizes, widths, heights)
    }

    fun draw(canvas: Canvas, scene: ReportScene) {
        val area = scene.frame.body
        val works = scene.works.take(MAX_WORKS)
        if (works.isEmpty()) return
        val plan = (MIN_TAIL..MAX_TAIL).map { plan(works.size, area, it) }
            .let { plans -> plans.firstOrNull { it.total <= area.height() } ?: plans.last() }
        val scale = (area.height() / plan.total).coerceAtMost(1f)
        var top = area.top + (area.height() - plan.total * scale) / 2
        var index = 0
        plan.sizes.forEachIndexed { row, n ->
            val w = plan.widths[row] * scale
            val h = w * ReportDraw.COVER_RATIO
            var left = area.centerX() - (n * w + (n - 1) * GAP) / 2
            repeat(n) {
                val work = works[index]
                val rect = RectF(left, top, left + w, top + h)
                ReportDraw.drawCover(canvas, rect, scene.covers[work.workId], corner = if (row == 0) 24f else 14f)
                if (index < RANKED) ReportDraw.drawRank(canvas, rect, index + 1)
                left += w + GAP
                index++
            }
            if (row == 0) drawLabel(canvas, scene, area.centerX(), top + h, scale)
            top += plan.heights[row] * scale + GAP
        }
    }

    private fun drawLabel(canvas: Canvas, scene: ReportScene, centerX: Float, coverBottom: Float, scale: Float) {
        val work = scene.works.first()
        val rating = work.rating?.takeIf { !scene.hideRatings }?.let { " · $it 分" }.orEmpty()
        val paint = ReportDraw.centered(ReportInks.LIGHT.ink, 36f * scale.coerceAtLeast(0.7f), bold = true)
        val label = ReportDraw.ellipsize("《${work.title}》", paint, scene.frame.body.width() * 0.8f) + rating
        canvas.drawText(label, centerX, coverBottom + 50f * scale, paint)
    }
}
