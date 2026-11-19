@file:Suppress("MagicNumber") // 分享图的画布坐标、字号和颜色

package com.acgcompass.feature.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

/** 分享图尺寸：竖版 9:16 适合故事和聊天，4:5 适合朋友圈、小红书。 */
enum class ReportSize(val label: String, val height: Int) {
    STORY("9:16", 1920),
    PORTRAIT("4:5", 1350),
}

/** 一张分享图的版式选项。[hidden] 里的作品不画进图里（统计数字不变）。 */
data class ReportStyle(
    val template: ReportTemplate = ReportTemplate.WALL,
    val size: ReportSize = ReportSize.STORY,
    val hideRatings: Boolean = false,
    val hidden: Set<String> = emptySet(),
)

/** 文字三种颜色：正文、次要、强调。 */
internal data class ReportInks(val ink: Int, val muted: Int, val accent: Int) {
    companion object {
        val LIGHT = ReportInks(0xFF16302F.toInt(), 0xFF5A6E6D.toInt(), 0xFF00696B.toInt())
        val ON_DARK = ReportInks(0xFFFFFFFF.toInt(), 0xDDFFFFFF.toInt(), 0xFFFFFFFF.toInt())
    }
}

/** 画布分区：标题区下沿、页脚上沿；4:5 时标题和页脚更紧凑。 */
internal class ReportFrame(size: ReportSize) {
    val width = 1080
    val height = size.height
    val compact = size == ReportSize.PORTRAIT
    val margin = 64f
    val headerBottom = if (compact) 380f else 470f
    val qrSize = if (compact) 160 else 200
    val footerTop = height - qrSize - 60f

    /** 标题区和页脚之间留给版式主体的区域。 */
    val body: RectF get() = RectF(margin, headerBottom, width - margin, footerTop - 24f)
}

/** 各版式共用的画法：封面（圆角、居中裁切，缺图画色块）、文字、名次角标。 */
internal object ReportDraw {
    const val BACKGROUND = 0xFFF1F5F5.toInt()
    const val PLACEHOLDER = 0xFFD7E3E2.toInt()
    const val COVER_RATIO = 4f / 3f
    private const val CORNER = 18f

    fun drawCover(canvas: Canvas, rect: RectF, cover: Bitmap?, corner: Float = CORNER) {
        val save = canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, corner, corner, Path.Direction.CW) })
        if (cover == null) {
            canvas.drawRect(rect, Paint().apply { color = PLACEHOLDER })
        } else {
            canvas.drawBitmap(
                cover,
                centerCrop(cover, rect.width() / rect.height()),
                rect,
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        canvas.restoreToCount(save)
    }

    /** 名次角标：封面左上角的圆形数字。 */
    fun drawRank(canvas: Canvas, rect: RectF, rank: Int, inks: ReportInks = ReportInks.LIGHT) {
        val radius = (rect.width() * 0.13f).coerceIn(16f, 34f)
        val cx = rect.left + radius + 8f
        val cy = rect.top + radius + 8f
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inks.accent })
        val paint = text(0xFFFFFFFF.toInt(), radius * 1.1f, bold = true).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("$rank", cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
    }

    /** 按目标宽高比居中裁切。 */
    fun centerCrop(bitmap: Bitmap, target: Float): Rect {
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

    fun ellipsize(value: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(value, paint, width.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()

    fun text(color: Int, size: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun centered(color: Int, size: Float, bold: Boolean = false) = text(color, size, bold).apply {
        textAlign = Paint.Align.CENTER
    }
}
