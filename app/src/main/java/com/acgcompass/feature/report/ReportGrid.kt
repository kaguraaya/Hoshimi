@file:Suppress("MagicNumber") // 分享图的画布坐标和字号

package com.acgcompass.feature.report

import android.graphics.Canvas
import android.graphics.RectF
import com.acgcompass.domain.report.ReportWork

/** 封面墙和前十榜单两种网格版式。 */
internal object ReportGrid {
    private const val MIN_COLUMNS = 3
    private const val MAX_COLUMNS = 7
    private const val BIG_EXTRA_CELLS = 3
    private const val CELL_GAP = 14f
    private const val BIG_SHARE = 0.2
    private const val TOP_TEN = 10
    private const val MIN_ROW_HEIGHT = 100f
    private const val COLUMN_GAP = 32f

    /**
     * 封面墙：评分前 20% 的作品占 2×2，其余 1×1；列数从 3 到 7 里挑最少、又能放下全部作品的（7 列也放不下时多出的不画），
     * 从上到下、从左到右找第一个放得下的位置，整体在区域里上下居中。
     */
    fun drawWall(canvas: Canvas, scene: ReportScene) {
        val area = scene.frame.body
        val works = scene.works
        val layout = wallLayout(area, works.size)
        val taken = Array(layout.rows) { BooleanArray(layout.columns) }
        val placed = works.mapIndexedNotNull { index, work ->
            val span = if (index < layout.big) 2 else 1
            findSlot(taken, span)?.let { (row, col) ->
                for (r in row until row + span) for (c in col until col + span) taken[r][c] = true
                Triple(work, row to col, span)
            }
        }
        val usedRows = (taken.indexOfLast { row -> row.any { it } } + 1).coerceAtLeast(1)
        val gridHeight = usedRows * layout.cellH + (usedRows - 1) * CELL_GAP
        val top0 = area.top + (area.height() - gridHeight) / 2
        placed.forEach { (work, slot, span) ->
            val (row, col) = slot
            val left = area.left + col * (layout.cellW + CELL_GAP)
            val top = top0 + row * (layout.cellH + CELL_GAP)
            val extra = CELL_GAP * (span - 1)
            val rect = RectF(left, top, left + layout.cellW * span + extra, top + layout.cellH * span + extra)
            ReportDraw.drawCover(canvas, rect, scene.covers[work.workId])
        }
    }

    private data class WallLayout(val columns: Int, val rows: Int, val cellW: Float, val cellH: Float, val big: Int)

    private fun wallLayout(area: RectF, count: Int): WallLayout {
        val options = (MIN_COLUMNS..MAX_COLUMNS).map { columns ->
            val cellW = (area.width() - CELL_GAP * (columns - 1)) / columns
            val cellH = cellW * ReportDraw.COVER_RATIO
            val rows = ((area.height() + CELL_GAP) / (cellH + CELL_GAP)).toInt().coerceAtLeast(1)
            val big = (count * BIG_SHARE).toInt().coerceAtMost(rows / 2)
            WallLayout(columns, rows, cellW, cellH, big)
        }
        return options.firstOrNull { it.rows * it.columns >= count + it.big * BIG_EXTRA_CELLS } ?: options.last()
    }

    private fun findSlot(taken: Array<BooleanArray>, span: Int): Pair<Int, Int>? {
        val columns = taken.firstOrNull()?.size ?: 0
        return (0..taken.size - span).asSequence()
            .flatMap { row -> (0..columns - span).asSequence().map { col -> row to col } }
            .firstOrNull { (row, col) ->
                (row until row + span).all { r -> (col until col + span).all { c -> !taken[r][c] } }
            }
    }

    /** 前十榜单：每行名次、封面、标题和评分；区域太矮（4:5）时排成两列各五名。 */
    fun drawTopTen(canvas: Canvas, scene: ReportScene) {
        val area = scene.frame.body
        val columns = if (area.height() / TOP_TEN < MIN_ROW_HEIGHT) 2 else 1
        val perColumn = TOP_TEN / columns
        val columnW = (area.width() - COLUMN_GAP * (columns - 1)) / columns
        val rowH = area.height() / perColumn
        scene.works.take(TOP_TEN).forEachIndexed { i, work ->
            val left = area.left + (i / perColumn) * (columnW + COLUMN_GAP)
            val row =
                RectF(left, area.top + (i % perColumn) * rowH, left + columnW, area.top + (i % perColumn + 1) * rowH)
            drawTopRow(canvas, scene, row, i + 1, work)
        }
    }

    private fun drawTopRow(canvas: Canvas, scene: ReportScene, row: RectF, rank: Int, work: ReportWork) {
        val inks = ReportInks.LIGHT
        val k = (row.height() / 115f).coerceAtMost(1f) * if (row.width() < 600f) 0.85f else 1f
        val midY = row.centerY()
        canvas.drawText("$rank", row.left, midY + 18f * k, ReportDraw.text(inks.accent, 48f * k, bold = true))
        val coverH = row.height() - 16f
        val coverLeft = row.left + 72f * k + 8f
        val cover = RectF(coverLeft, row.top + 8f, coverLeft + coverH / ReportDraw.COVER_RATIO, row.top + 8f + coverH)
        ReportDraw.drawCover(canvas, cover, scene.covers[work.workId], corner = 12f)
        val textLeft = cover.right + 24f
        val titlePaint = ReportDraw.text(inks.ink, 38f * k, bold = true)
        canvas.drawText(
            ReportDraw.ellipsize(work.title, titlePaint, row.right - textLeft),
            textLeft,
            midY - 4f,
            titlePaint,
        )
        val meta = listOfNotNull(
            work.rating?.takeIf { !scene.hideRatings }?.let { "我的评分 $it" },
            work.community?.let { "Bangumi %.1f".format(it) },
            work.year?.takeIf { row.width() >= 600f }?.let { "$it 年" },
        ).joinToString(" · ")
        val metaPaint = ReportDraw.text(inks.muted, 30f * k)
        canvas.drawText(
            ReportDraw.ellipsize(meta, metaPaint, row.right - textLeft),
            textLeft,
            midY + 44f * k,
            metaPaint,
        )
    }
}
