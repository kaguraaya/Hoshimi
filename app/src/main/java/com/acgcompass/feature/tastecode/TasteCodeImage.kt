package com.acgcompass.feature.tastecode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * 保存到相册的口味码图片：标题、昵称、二维码和一句怎么用，白底竖版。
 * 二维码四周留足白边，截图或被压缩后也能识别。
 */
object TasteCodeImage {
    private const val WIDTH = 1080
    private const val HEIGHT = 1440
    private const val QR_SIZE = 760
    private const val QR_TOP = 360
    private const val CARD_MARGIN = 72f
    private const val CARD_RADIUS = 48f
    private const val TITLE_SIZE = 64f
    private const val SUBTITLE_SIZE = 40f
    private const val HINT_SIZE = 34f
    private const val TITLE_Y = 200f
    private const val SUBTITLE_Y = 276f
    private const val HINT_GAP = 110f
    private const val LINE_GAP = 52f
    private const val BACKGROUND = 0xFFEFF4F4.toInt()
    private const val INK = 0xFF1B2B2B.toInt()
    private const val MUTED = 0xFF5B6B6B.toInt()

    fun render(code: String, nickname: String?): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND)
        val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(
            RectF(CARD_MARGIN, CARD_MARGIN, WIDTH - CARD_MARGIN, HEIGHT - CARD_MARGIN),
            CARD_RADIUS,
            CARD_RADIUS,
            card,
        )
        val center = WIDTH / 2f
        canvas.drawText("Hoshimi 口味码", center, TITLE_Y, text(INK, TITLE_SIZE, bold = true))
        canvas.drawText(
            nickname?.let { "${it}的口味" } ?: "来比比我们的口味",
            center,
            SUBTITLE_Y,
            text(MUTED, SUBTITLE_SIZE),
        )
        val qr = QrCodes.encode(code, QR_SIZE)
        canvas.drawBitmap(qr, (WIDTH - QR_SIZE) / 2f, QR_TOP.toFloat(), null)
        val hint = text(MUTED, HINT_SIZE)
        val hintY = QR_TOP + QR_SIZE + HINT_GAP
        canvas.drawText("在 Hoshimi「我的 → 打开口味码」里", center, hintY, hint)
        canvas.drawText("选择这张图片，就能看到我们的口味相性", center, hintY + LINE_GAP, hint)
        return bitmap
    }

    private fun text(color: Int, size: Float, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        textAlign = Paint.Align.CENTER
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
}
