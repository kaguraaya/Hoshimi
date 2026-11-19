@file:Suppress("MagicNumber") // 分享图的画布坐标和字号

package com.acgcompass.feature.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.acgcompass.domain.report.ReportWork
import com.acgcompass.domain.report.WatchReport
import com.acgcompass.feature.tastecode.QrCodes

/** 分享图的版式。 */
enum class ReportTemplate(val label: String) {
    /** 封面墙：评分高的占大格。 */
    WALL("封面墙"),

    /** 前十榜单：封面 + 名次 + 评分。 */
    TOP_TEN("前十榜单"),
}

/**
 * 回顾分享图（1080×1920 竖版）：标题和三个数字、版式主体、页脚（项目二维码、「Hoshimi · 用口味找番」、时间范围）。
 * 封面由调用方事先下载好（[covers]：作品 id → 位图），缺图的格子画成色块。
 */
@Suppress("TooManyFunctions") // 每块画面一个小函数
object ReportImage {
    const val PROJECT_URL = "https://github.com/kaguraaya/Hoshimi"
    private const val WIDTH = 1080
    private const val HEIGHT = 1920
    private const val MARGIN = 64f
    private const val HEADER_BOTTOM = 470f
    private const val SUMMARY_Y = 420f
    private const val MIN_COLUMNS = 3
    private const val MAX_COLUMNS = 7
    private const val FOOTER_TOP = 1660f
    private const val BIG_EXTRA_CELLS = 3
    private const val CELL_GAP = 14f
    private const val COVER_RATIO = 4f / 3f
    private const val BIG_SHARE = 0.2
    private const val TOP_TEN = 10
    private const val QR_SIZE = 200
    private const val CORNER = 18f
    private const val BACKGROUND = 0xFFF1F5F5.toInt()
    private const val INK = 0xFF16302F.toInt()
    private const val MUTED = 0xFF5A6E6D.toInt()
    private const val ACCENT = 0xFF00696B.toInt()
    private const val PLACEHOLDER = 0xFFD7E3E2.toInt()

    fun render(
        report: WatchReport,
        template: ReportTemplate,
        covers: Map<String, Bitmap>,
        hideRatings: Boolean,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        drawHeader(canvas, report, hideRatings)
        val area = RectF(MARGIN, HEADER_BOTTOM, WIDTH - MARGIN, FOOTER_TOP - 24f)
        when (template) {
            ReportTemplate.WALL -> drawWall(canvas, area, report.finished, covers)
            ReportTemplate.TOP_TEN -> drawTopTen(canvas, area, report.finished.take(TOP_TEN), covers, hideRatings)
        }
        drawFooter(canvas, report)
        return bitmap
    }

    private fun drawHeader(canvas: Canvas, report: WatchReport, hideRatings: Boolean) {
        canvas.drawText("我的 ${report.period.label}", MARGIN, 150f, text(INK, 76f, bold = true))
        canvas.drawText("番剧回顾 · ${report.period.range}", MARGIN, 210f, text(MUTED, 36f))
        val stats = listOf("${report.finished.size}" to "部看完", "${report.episodes}" to "集", "${report.hours}" to "小时")
        val columnWidth = (WIDTH - MARGIN * 2) / stats.size
        stats.forEachIndexed { i, (number, unit) ->
            val x = MARGIN + i * columnWidth
            canvas.drawText(number, x, 310f, text(ACCENT, 72f, bold = true))
            canvas.drawText(unit, x, 360f, text(MUTED, 32f))
        }
        val summary = buildList {
            if (!hideRatings) report.averageRating?.let { add("平均 %.1f 分".format(it)) }
            report.topGenres.takeIf { it.isNotEmpty() }?.let { add("最常看 " + it.joinToString("、")) }
        }.joinToString(" · ")
        if (summary.isNotEmpty()) {
            canvas.drawText(
                ellipsize(summary, text(INK, 32f), WIDTH - MARGIN * 2),
                MARGIN,
                SUMMARY_Y,
                text(INK, 32f),
            )
        }
    }

    /**
     * 封面墙：评分前 20% 的作品占 2×2，其余 1×1；列数从 3 到 7 里挑最少、又能放下全部作品的（7 列也放不下时多出的不画），
     * 从上到下、从左到右找第一个放得下的位置，整体在区域里上下居中。
     */
    private fun drawWall(canvas: Canvas, area: RectF, works: List<ReportWork>, covers: Map<String, Bitmap>) {
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
            drawCover(canvas, rect, covers[work.workId])
        }
    }

    private data class WallLayout(val columns: Int, val rows: Int, val cellW: Float, val cellH: Float, val big: Int)

    private fun wallLayout(area: RectF, count: Int): WallLayout {
        val options = (MIN_COLUMNS..MAX_COLUMNS).map { columns ->
            val cellW = (area.width() - CELL_GAP * (columns - 1)) / columns
            val cellH = cellW * COVER_RATIO
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

    private fun drawTopTen(
        canvas: Canvas,
        area: RectF,
        works: List<ReportWork>,
        covers: Map<String, Bitmap>,
        hideRatings: Boolean,
    ) {
        val rowH = area.height() / TOP_TEN
        val coverH = rowH - 16f
        val coverW = coverH / COVER_RATIO
        works.forEachIndexed { i, work ->
            val top = area.top + i * rowH
            canvas.drawText("${i + 1}", area.left, top + rowH / 2 + 18f, text(ACCENT, 48f, bold = true))
            val coverLeft = area.left + 80f
            drawCover(canvas, RectF(coverLeft, top + 8f, coverLeft + coverW, top + 8f + coverH), covers[work.workId])
            val textLeft = coverLeft + coverW + 28f
            val titlePaint = text(INK, 38f, bold = true)
            canvas.drawText(
                ellipsize(work.title, titlePaint, area.right - textLeft),
                textLeft,
                top + rowH / 2 - 4f,
                titlePaint,
            )
            val meta = listOfNotNull(
                work.rating?.takeIf { !hideRatings }?.let { "我的评分 $it" },
                work.community?.let { "Bangumi %.1f".format(it) },
                work.year?.let { "$it 年" },
            ).joinToString(" · ")
            canvas.drawText(meta, textLeft, top + rowH / 2 + 46f, text(MUTED, 30f))
        }
    }

    private fun drawCover(canvas: Canvas, rect: RectF, cover: Bitmap?) {
        val save = canvas.save()
        val path = android.graphics.Path().apply {
            addRoundRect(
                rect,
                CORNER,
                CORNER,
                android.graphics.Path.Direction.CW,
            )
        }
        canvas.clipPath(path)
        if (cover == null) {
            canvas.drawRect(rect, Paint().apply { color = PLACEHOLDER })
        } else {
            canvas.drawBitmap(cover, centerCrop(cover, rect), rect, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        canvas.restoreToCount(save)
    }

    /** 裁成和格子一样的比例（居中裁切）。 */
    private fun centerCrop(bitmap: Bitmap, rect: RectF): Rect {
        val target = rect.width() / rect.height()
        val source = bitmap.width.toFloat() / bitmap.height
        return if (source > target) {
            val w = (bitmap.height * target).toInt()
            val x = (bitmap.width - w) / 2
            Rect(x, 0, x + w, bitmap.height)
        } else {
            val h = (bitmap.width / target).toInt()
            val y = (bitmap.height - h) / 2
            Rect(0, y, bitmap.width, y + h)
        }
    }

    private fun drawFooter(canvas: Canvas, report: WatchReport) {
        val qrTop = FOOTER_TOP + 20f
        val qr = QrCodes.encode(PROJECT_URL, QR_SIZE)
        canvas.drawRoundRect(
            RectF(
                MARGIN - 8f,
                qrTop - 8f,
                MARGIN + QR_SIZE + 8f,
                qrTop + QR_SIZE + 8f,
            ),
            16f,
            16f,
            Paint().apply {
                color = Color.WHITE
            },
        )
        canvas.drawBitmap(qr, MARGIN, qrTop, null)
        val left = MARGIN + QR_SIZE + 40f
        canvas.drawText("Hoshimi · 用口味找番", left, qrTop + 70f, text(INK, 40f, bold = true))
        canvas.drawText(report.period.label + " · " + report.period.range, left, qrTop + 125f, text(MUTED, 30f))
        canvas.drawText("github.com/kaguraaya/Hoshimi", left, qrTop + 172f, text(MUTED, 28f))
    }

    private fun ellipsize(value: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(value, paint, width, TextUtils.TruncateAt.END).toString()

    private fun text(color: Int, size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
}
