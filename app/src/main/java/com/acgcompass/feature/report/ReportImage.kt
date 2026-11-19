@file:Suppress("MagicNumber") // 分享图的画布坐标和字号

package com.acgcompass.feature.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.acgcompass.domain.report.ReportWork
import com.acgcompass.domain.report.WatchReport
import com.acgcompass.feature.tastecode.QrCodes

/** 分享图的版式。 */
enum class ReportTemplate(val label: String) {
    /** 封面墙：评分高的占大格。 */
    WALL("封面墙"),

    /** 前十榜单：封面 + 名次 + 评分。 */
    TOP_TEN("前十榜单"),

    /** 神作金字塔：最高分一张大图，往下越来越小。 */
    PYRAMID("神作金字塔"),

    /** 时间线：按月一行，当月最高分的那张大一档。 */
    TIMELINE("时间线"),

    /** 极简海报：最爱的封面做模糊背景，大字统计 + 前五名。 */
    POSTER("极简海报"),

    /** 拍立得：散落的相片卡片，高分的更大、压在上面。 */
    POLAROID("拍立得"),
}

/** 一张分享图要用的数据：统计来自 [report]，画进图里的作品是 [works]（去掉了不想公开的）。 */
internal class ReportScene(
    val report: WatchReport,
    val works: List<ReportWork>,
    val covers: Map<String, Bitmap>,
    val hideRatings: Boolean,
    val frame: ReportFrame,
)

/**
 * 回顾分享图（宽 1080，竖版 9:16 或 4:5）：标题和三个数字、版式主体、页脚（项目二维码、「Hoshimi · 用口味找番」、时间范围）。
 * 封面由调用方事先下载好（[covers]：作品 id → 位图），缺图的格子画成色块。
 */
object ReportImage {
    const val PROJECT_URL = "https://github.com/kaguraaya/Hoshimi"

    fun render(report: WatchReport, style: ReportStyle, covers: Map<String, Bitmap>): Bitmap {
        val frame = ReportFrame(style.size)
        val scene = ReportScene(
            report = report,
            works = report.finished.filterNot { it.workId in style.hidden },
            covers = covers,
            hideRatings = style.hideRatings,
            frame = frame,
        )
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (style.template == ReportTemplate.POSTER) {
            ReportPoster.draw(canvas, scene)
            drawFooter(canvas, scene, ReportInks.ON_DARK)
            return bitmap
        }
        canvas.drawColor(if (style.template == ReportTemplate.POLAROID) ReportPolaroid.PAPER else ReportDraw.BACKGROUND)
        drawHeader(canvas, scene)
        when (style.template) {
            ReportTemplate.WALL -> ReportGrid.drawWall(canvas, scene)
            ReportTemplate.TOP_TEN -> ReportGrid.drawTopTen(canvas, scene)
            ReportTemplate.PYRAMID -> ReportPyramid.draw(canvas, scene)
            ReportTemplate.TIMELINE -> ReportTimeline.draw(canvas, scene)
            ReportTemplate.POLAROID -> ReportPolaroid.draw(canvas, scene)
            ReportTemplate.POSTER -> Unit
        }
        drawFooter(canvas, scene, ReportInks.LIGHT)
        return bitmap
    }

    /** 统计下面的一行小结：平均分（可隐藏）和最常看的题材。 */
    internal fun summary(scene: ReportScene): String = buildList {
        if (!scene.hideRatings) scene.report.averageRating?.let { add("平均 %.1f 分".format(it)) }
        scene.report.topGenres.takeIf { it.isNotEmpty() }?.let { add("最常看 " + it.joinToString("、")) }
    }.joinToString(" · ")

    private fun drawHeader(canvas: Canvas, scene: ReportScene) {
        val f = scene.frame
        val report = scene.report
        val inks = ReportInks.LIGHT
        val k = if (f.compact) 0.82f else 1f
        val y = if (f.compact) {
            floatArrayOf(
                118f,
                168f,
                252f,
                294f,
                342f,
            )
        } else {
            floatArrayOf(150f, 210f, 310f, 360f, 420f)
        }
        canvas.drawText("我的 ${report.period.label}", f.margin, y[0], ReportDraw.text(inks.ink, 76f * k, bold = true))
        canvas.drawText("番剧回顾 · ${report.period.range}", f.margin, y[1], ReportDraw.text(inks.muted, 36f * k))
        val stats = listOf("${report.finished.size}" to "部看完", "${report.episodes}" to "集", "${report.hours}" to "小时")
        val columnWidth = (f.width - f.margin * 2) / stats.size
        stats.forEachIndexed { i, (number, unit) ->
            val x = f.margin + i * columnWidth
            canvas.drawText(number, x, y[2], ReportDraw.text(inks.accent, 72f * k, bold = true))
            canvas.drawText(unit, x, y[3], ReportDraw.text(inks.muted, 32f * k))
        }
        val summary = summary(scene)
        if (summary.isNotEmpty()) {
            val paint = ReportDraw.text(inks.ink, 32f * k)
            canvas.drawText(ReportDraw.ellipsize(summary, paint, f.width - f.margin * 2), f.margin, y[4], paint)
        }
    }

    private fun drawFooter(canvas: Canvas, scene: ReportScene, inks: ReportInks) {
        val f = scene.frame
        val qr = f.qrSize.toFloat()
        val qrTop = f.footerTop + 20f
        canvas.drawRoundRect(
            RectF(f.margin - 8f, qrTop - 8f, f.margin + qr + 8f, qrTop + qr + 8f),
            16f,
            16f,
            Paint().apply { color = Color.WHITE },
        )
        canvas.drawBitmap(QrCodes.encode(PROJECT_URL, f.qrSize), f.margin, qrTop, null)
        val left = f.margin + qr + 40f
        val k = qr / 200f
        val report = scene.report
        canvas.drawText("Hoshimi · 用口味找番", left, qrTop + 70f * k, ReportDraw.text(inks.ink, 40f * k, bold = true))
        canvas.drawText(
            report.period.label + " · " + report.period.range,
            left,
            qrTop + 125f * k,
            ReportDraw.text(inks.muted, 30f * k),
        )
        canvas.drawText("github.com/kaguraaya/Hoshimi", left, qrTop + 172f * k, ReportDraw.text(inks.muted, 28f * k))
    }
}
