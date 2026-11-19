@file:Suppress("MagicNumber") // 分享图的画布坐标、字号和颜色

package com.acgcompass.feature.report

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.acgcompass.domain.report.ReportWork
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * 拍立得：前九部（4:5 时前六部）做成略微倾斜的相片卡片散落摆放。名次越高越大、越靠近中间、压在越上面；
 * 卡片下沿写片名和评分，区域底部一句倾斜的短句。位置和角度由作品 id 决定，同一份回顾每次画出来都一样。
 */
internal object ReportPolaroid {
    const val PAPER = 0xFFF3EEE6.toInt()
    private const val CARD = 0xFFFFFFFF.toInt()
    private const val TAPE = 0x66E8C77A
    private const val SHADOW = 0x33000000
    private const val MAX_CARDS = 9
    private const val MAX_CARDS_COMPACT = 6
    private const val COLUMNS = 3
    private const val PHRASE_HEIGHT = 80f
    private const val MAX_TILT = 7f
    private const val JITTER = 0.08f
    private const val SKEW = -0.22f
    private const val FRAME = 0.06f
    private const val CAPTION = 0.26f

    /** 一张卡片：中心、宽度、角度和名次。 */
    data class Card(val x: Float, val y: Float, val width: Float, val angle: Float, val rank: Int)

    /** 排好九宫格里的位置：名次高的放离中心近的格子，宽度按名次放大或缩小。纯计算，方便测试。 */
    fun layout(ids: List<String>, area: RectF): List<Card> {
        val n = ids.size.coerceAtMost(MAX_CARDS)
        if (n == 0) return emptyList()
        val rows = ceil(n / COLUMNS.toFloat()).toInt()
        val cellW = area.width() / COLUMNS
        val cellH = area.height() / rows
        val cells = (0 until rows * COLUMNS).map { i -> (i / COLUMNS) to (i % COLUMNS) }
            .sortedBy { (r, c) -> hypot((c + 0.5f) - COLUMNS / 2f, (r + 0.5f) - rows / 2f) }
        val base = minOf(cellW * 0.96f, cellH * 0.96f / cardRatio())
        return (0 until n).map { rank ->
            val (r, c) = cells[rank]
            val seed = ids[rank].hashCode()
            val scale = when (rank) {
                0 -> 1.14f
                1, 2 -> 1f
                else -> 0.9f
            }
            Card(
                x = area.left + (c + 0.5f) * cellW + jitter(seed, 1) * cellW * JITTER,
                y = area.top + (r + 0.5f) * cellH + jitter(seed, 2) * cellH * JITTER,
                width = base * scale,
                angle = jitter(seed, 3) * MAX_TILT,
                rank = rank,
            )
        }
    }

    /** 卡片高宽比：白边 + 3:4 封面 + 底部写字的白边。 */
    private fun cardRatio(): Float = (1f - 2 * FRAME) * ReportDraw.COVER_RATIO + FRAME + CAPTION

    /** -1 到 1 之间、由种子决定的数。 */
    private fun jitter(seed: Int, salt: Int): Float {
        val h = (seed * 31 + salt * 0x9E3779B9.toInt()).let { it xor (it ushr 15) } * 0x2C1B3C6D
        return ((h ushr 8) and 0xFFFF) / 32767.5f - 1f
    }

    fun draw(canvas: Canvas, scene: ReportScene) {
        val body = scene.frame.body
        val area = RectF(body.left, body.top, body.right, body.bottom - PHRASE_HEIGHT)
        val works = scene.works.take(if (scene.frame.compact) MAX_CARDS_COMPACT else MAX_CARDS)
        val cards = layout(works.map { it.workId }, area)
        cards.sortedByDescending { it.rank }.forEach { card -> drawCard(canvas, scene, card, works[card.rank]) }
        drawPhrase(canvas, scene, body)
    }

    private fun drawCard(canvas: Canvas, scene: ReportScene, card: Card, work: ReportWork) {
        val w = card.width
        val h = w * cardRatio()
        val save = canvas.save()
        canvas.rotate(card.angle, card.x, card.y)
        val rect = RectF(card.x - w / 2, card.y - h / 2, card.x + w / 2, card.y + h / 2)
        canvas.drawRect(
            RectF(rect.left + 6f, rect.top + 10f, rect.right + 6f, rect.bottom + 10f),
            Paint().apply {
                color = SHADOW
            },
        )
        canvas.drawRect(rect, Paint().apply { color = CARD })
        val pad = w * FRAME
        val coverW = w - pad * 2
        val cover =
            RectF(rect.left + pad, rect.top + pad, rect.right - pad, rect.top + pad + coverW * ReportDraw.COVER_RATIO)
        ReportDraw.drawCover(canvas, cover, scene.covers[work.workId], corner = 2f)
        val captionTop = cover.bottom
        val titlePaint = handwriting(ReportInks.LIGHT.ink, (w * 0.09f).coerceIn(22f, 40f), bold = true)
        canvas.drawText(
            ReportDraw.ellipsize(work.title, titlePaint, coverW),
            cover.left,
            captionTop + w * 0.12f,
            titlePaint,
        )
        val meta = listOfNotNull(
            work.rating?.takeIf { !scene.hideRatings }?.let { "$it 分" },
            if (card.rank == 0) "最爱" else work.year?.let { "$it" },
        ).joinToString(" · ")
        val metaPaint = handwriting(ReportInks.LIGHT.muted, (w * 0.07f).coerceIn(18f, 30f))
        canvas.drawText(meta, cover.left, captionTop + w * 0.21f, metaPaint)
        val tapeW = w * 0.34f
        canvas.drawRect(
            RectF(card.x - tapeW / 2, rect.top - 14f, card.x + tapeW / 2, rect.top + 22f),
            Paint().apply {
                color = TAPE
            },
        )
        canvas.restoreToCount(save)
    }

    private fun drawPhrase(canvas: Canvas, scene: ReportScene, body: RectF) {
        val report = scene.report
        val favorite = scene.works.firstOrNull()?.title
        val phrase = buildString {
            append("${report.period.label}，看完 ${report.finished.size} 部")
            favorite?.let { append("，最爱《$it》") }
        }
        val paint = handwriting(ReportInks.LIGHT.ink, if (scene.frame.compact) 32f else 38f).apply {
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(ReportDraw.ellipsize(phrase, paint, body.width()), body.centerX(), body.bottom - 18f, paint)
    }

    /** 没有内置手写字体：用倾斜的系统字体代替。 */
    private fun handwriting(color: Int, size: Float, bold: Boolean = false) = ReportDraw.text(color, size, bold).apply {
        textSkewX = SKEW
    }
}
