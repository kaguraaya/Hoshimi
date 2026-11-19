@file:Suppress("MagicNumber") // 分享图的画布坐标、字号和颜色

package com.acgcompass.feature.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/**
 * 极简海报：最爱作品的封面放大模糊铺满整张图，叠一层从封面取的主色到黑色的渐变；
 * 中间大字写看完部数，下面集数、时长和小结，再下面前五名小封面（可带评分）。没有封面时用应用主色渐变。
 */
internal object ReportPoster {
    private const val TOP_FIVE = 5
    private const val BLUR_STEPS = 3
    private const val FALLBACK = 0xFF0B3B3C.toInt()
    private const val COVER_GAP = 24f
    private const val OVERLAY_TOP = 0xB0
    private const val OVERLAY_BOTTOM = 0xE6

    fun draw(canvas: Canvas, scene: ReportScene) {
        val f = scene.frame
        val cover = scene.works.firstOrNull()?.let { scene.covers[it.workId] }
        val tint = drawBackground(canvas, f, cover)
        canvas.drawPaint(
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, f.height.toFloat(),
                    withAlpha(darken(tint), OVERLAY_TOP),
                    withAlpha(Color.BLACK, OVERLAY_BOTTOM),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        drawStats(canvas, scene)
        drawTopFive(canvas, scene)
    }

    /** 封面缩到很小再放大，就是一张柔和的模糊图；返回封面的平均色，用来调渐变。 */
    private fun drawBackground(canvas: Canvas, f: ReportFrame, cover: Bitmap?): Int {
        if (cover == null) {
            canvas.drawColor(FALLBACK)
            return FALLBACK
        }
        val full = RectF(0f, 0f, f.width.toFloat(), f.height.toFloat())
        val crop = ReportDraw.centerCrop(cover, full.width() / full.height())
        var small = Bitmap.createBitmap(cover, crop.left, crop.top, crop.width(), crop.height())
        var w = 48
        repeat(BLUR_STEPS) {
            val h = (w * full.height() / full.width()).toInt().coerceAtLeast(1)
            small = Bitmap.createScaledBitmap(small, w, h, true)
            w /= 2
        }
        canvas.drawBitmap(small, null, full, Paint(Paint.FILTER_BITMAP_FLAG))
        return averageColor(small)
    }

    private fun drawStats(canvas: Canvas, scene: ReportScene) {
        val f = scene.frame
        val y = if (f.compact) 0.68f else 1f
        val s = if (f.compact) 0.78f else 1f
        val cx = f.width / 2f
        val report = scene.report
        val inks = ReportInks.ON_DARK
        canvas.drawText("我的 ${report.period.label}", cx, 230f * y, ReportDraw.centered(inks.ink, 68f * s, bold = true))
        canvas.drawText("番剧回顾 · ${report.period.range}", cx, 296f * y, ReportDraw.centered(inks.muted, 34f * s))
        canvas.drawText("${report.finished.size}", cx, 640f * y, ReportDraw.centered(inks.ink, 280f * s, bold = true))
        canvas.drawText("部看完", cx, 720f * y, ReportDraw.centered(inks.muted, 44f * s))
        canvas.drawText(
            "${report.episodes} 集 · ${report.hours} 小时",
            cx,
            830f * y,
            ReportDraw.centered(inks.ink, 48f * s, bold = true),
        )
        val summary = ReportImage.summary(scene)
        if (summary.isNotEmpty()) {
            val paint = ReportDraw.centered(inks.muted, 34f * s)
            canvas.drawText(ReportDraw.ellipsize(summary, paint, f.width - f.margin * 2), cx, 896f * y, paint)
        }
    }

    private fun drawTopFive(canvas: Canvas, scene: ReportScene) {
        val f = scene.frame
        val works = scene.works.take(TOP_FIVE)
        if (works.isEmpty()) return
        val y = if (f.compact) 0.68f else 1f
        val s = if (f.compact) 0.8f else 1f
        val inks = ReportInks.ON_DARK
        canvas.drawText("前 ${works.size} 名", f.width / 2f, 1060f * y, ReportDraw.centered(inks.muted, 32f * s))
        val w = (f.width - f.margin * 2 - COVER_GAP * (TOP_FIVE - 1)) / TOP_FIVE * s
        val h = w * ReportDraw.COVER_RATIO
        val top = 1100f * y
        var left = f.width / 2f - (works.size * w + (works.size - 1) * COVER_GAP) / 2
        works.forEach { work ->
            val rect = RectF(left, top, left + w, top + h)
            ReportDraw.drawCover(canvas, rect, scene.covers[work.workId], corner = 14f)
            val titlePaint = ReportDraw.centered(inks.ink, 26f * s, bold = true)
            canvas.drawText(
                ReportDraw.ellipsize(work.title, titlePaint, w),
                rect.centerX(),
                rect.bottom + 40f * s,
                titlePaint,
            )
            work.rating?.takeIf { !scene.hideRatings }?.let {
                canvas.drawText(
                    "$it 分",
                    rect.centerX(),
                    rect.bottom + 76f * s,
                    ReportDraw.centered(inks.muted, 26f * s),
                )
            }
            left += w + COVER_GAP
        }
    }

    private fun averageColor(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val n = pixels.size.coerceAtLeast(1)
        return Color.rgb(
            pixels.sumOf { Color.red(it) } / n,
            pixels.sumOf { Color.green(it) } / n,
            pixels.sumOf { Color.blue(it) } / n,
        )
    }

    private fun darken(color: Int): Int = Color.rgb(Color.red(color) / 2, Color.green(color) / 2, Color.blue(color) / 2)

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
