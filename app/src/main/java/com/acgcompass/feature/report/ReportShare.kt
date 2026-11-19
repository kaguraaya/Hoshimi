package com.acgcompass.feature.report

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.acgcompass.core.ui.GallerySaver
import com.acgcompass.domain.report.WatchReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** 生成回顾分享图：先下好封面（关闭硬件位图才能画进图里），再画图；存进相册或调起系统分享。 */
internal object ReportShare {
    private const val COVER_PIXELS = 480
    private const val MAX_COVERS = 40

    suspend fun render(context: Context, report: WatchReport, template: ReportTemplate, hideRatings: Boolean): Bitmap {
        val covers = loadCovers(context, report)
        return withContext(Dispatchers.Default) { ReportImage.render(report, template, covers, hideRatings) }
    }

    suspend fun save(context: Context, bitmap: Bitmap): Boolean = GallerySaver.savePng(
        context,
        bitmap,
        "Hoshimi_report",
    )

    /** 存进相册后把相册里的这张图交给系统分享。 */
    suspend fun share(context: Context, bitmap: Bitmap): Boolean {
        val uri = GallerySaver.savePngUri(context, bitmap, "Hoshimi_report") ?: return false
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "分享回顾").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    private suspend fun loadCovers(context: Context, report: WatchReport): Map<String, Bitmap> = coroutineScope {
        report.finished.take(MAX_COVERS).filter { it.coverUrl != null }.map { work ->
            async {
                val request = ImageRequest.Builder(context)
                    .data(work.coverUrl)
                    .allowHardware(false)
                    .size(COVER_PIXELS)
                    .build()
                val result = runCatching { context.imageLoader.execute(request) }.getOrNull()
                val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                bitmap?.let { work.workId to it }
            }
        }.awaitAll().filterNotNull().toMap()
    }
}
