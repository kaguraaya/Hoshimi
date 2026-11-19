package com.acgcompass.feature.report

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.TonalFilterChip
import com.acgcompass.core.ui.LocalAppMessenger
import com.acgcompass.domain.report.WatchReport
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/** 分享图：选版式和尺寸、隐藏评分，下面是实时预览；保存或分享用的就是预览这张图。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShareCard(
    state: WatchReportUiState,
    onSelectTemplate: (ReportTemplate) -> Unit,
    onSelectSize: (ReportSize) -> Unit,
    onToggleHideRatings: () -> Unit,
) {
    val report = state.report ?: return
    val style = state.style
    PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("分享图", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReportTemplate.entries.forEach { template ->
                    TonalFilterChip(template.label, template == style.template, { onSelectTemplate(template) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReportSize.entries.forEach { size ->
                    TonalFilterChip(size.label, size == style.size, { onSelectSize(size) })
                }
                TonalFilterChip("隐藏评分", style.hideRatings, onToggleHideRatings)
            }
            if (report.enoughForShare) {
                Text(
                    "长按上面的作品可以不放进图里，统计数字不变。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PreviewAndActions(report, style)
            } else {
                Text(
                    "看完 ${WatchReport.MIN_FINISHED} 部以上才能生成分享图，再看几部再来吧。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviewAndActions(report: WatchReport, style: ReportStyle) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val showMessage = LocalAppMessenger.current
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(report, style) {
        rendering = true
        preview = renderOrNull { ReportShare.render(context, report, style) }
        rendering = false
    }
    PreviewImage(preview, rendering, style.size)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        fun run(action: suspend (Bitmap) -> Boolean, done: String) {
            busy = true
            scope.launch {
                val image = preview?.takeIf { !rendering }
                    ?: renderOrNull { ReportShare.render(context, report, style) }
                val ok = image != null && renderOrNull { action(image) } == true
                busy = false
                showMessage(if (ok) done else "生成失败，稍后再试")
            }
        }
        val enabled = !busy
        FilledTonalButton(onClick = { run({ ReportShare.save(context, it) }, "已保存到相册") }, enabled = enabled) {
            Text("存图")
        }
        Button(onClick = { run({ ReportShare.share(context, it) }, "已生成，选择要分享到哪里") }, enabled = enabled) {
            Text(if (busy) "生成中…" else "分享")
        }
    }
}

@Composable
private fun PreviewImage(preview: Bitmap?, rendering: Boolean, size: ReportSize) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth(PREVIEW_WIDTH)
                .aspectRatio(PREVIEW_IMAGE_WIDTH / size.height)
                .clip(PrismCorner.Thumb)
                .background(Surfaces.raisedColor),
            contentAlignment = Alignment.Center,
        ) {
            preview?.let {
                Image(
                    it.asImageBitmap(),
                    contentDescription = "分享图预览",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (rendering) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
}

/** 生成失败时返回 null；页面离开或选项变化时的取消照常抛出，不当成失败。 */
// 画图、读封面、写相册的任何异常都只提示「生成失败」
@Suppress("TooGenericExceptionCaught", "SwallowedException")
private suspend fun <T> renderOrNull(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

private const val PREVIEW_WIDTH = 0.62f
private const val PREVIEW_IMAGE_WIDTH = 1080f
