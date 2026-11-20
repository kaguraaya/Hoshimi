package com.acgcompass.feature.detail

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import coil.imageLoader
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.domain.cache.CacheAssetKind
import com.acgcompass.domain.visualnovel.VisualNovelDetail
import com.acgcompass.domain.visualnovel.VisualNovelImage
import com.acgcompass.feature.visualnovel.ManagedImageSpec
import com.acgcompass.feature.visualnovel.ManagedVisualNovelImage
import com.acgcompass.feature.visualnovel.managedImageRequest
import kotlinx.coroutines.delay

@Composable
internal fun VndbExtensionSection(
    detail: VisualNovelDetail?,
    loading: Boolean,
    error: String?,
    onOpenWork: (String) -> Unit,
    onOpenProducer: (String, String) -> Unit,
) {
    if (detail == null) {
        if (loading || error != null) {
            SectionCard(title = "VNDB 扩展资料") {
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(error ?: "正在后台加载，页面其他内容可正常使用")
            }
        }
        return
    }

    var revealed by remember(detail.id) { mutableStateOf(emptySet<String>()) }
    var openedImage by remember(detail.id) { mutableStateOf<VisualNovelImage?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    VndbMediaPreload(detail)

    VndbScreenshotSection(
        detail = detail,
        revealed = revealed,
        onReveal = { revealed = revealed + it },
        onOpen = { openedImage = it },
    )
    VndbOverviewSections(detail)
    VndbProducerSection(detail, onOpenProducer, context)
    VndbCreditsSections(detail)
    VndbRelationsSection(detail, onOpenWork)

    openedImage?.let { image ->
        VndbScreenshotViewer(
            detail = detail,
            image = image,
            context = context,
            onDismiss = { openedImage = null },
        )
    }
}

@Composable
internal fun VndbMediaPreload(detail: VisualNovelDetail) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    val coverPreload = detail.cover?.takeUnless { it.sensitive }?.let { image ->
        managedImageRequest(
            url = image.url,
            workId = detail.id,
            kind = CacheAssetKind.COVER,
            adult = detail.adult,
        )
    }
    val screenshotPreload = detail.screenshots.firstOrNull()?.takeUnless { it.sensitive }?.let { image ->
        managedImageRequest(
            url = image.url,
            workId = detail.id,
            kind = CacheAssetKind.SCREENSHOT_ORIGINAL,
            adult = detail.adult,
        )
    }
    LaunchedEffect(detail.id, detail.cover?.url, detail.screenshots.firstOrNull()?.url) {
        delay(VNDB_ORIGINAL_PRELOAD_DELAY_MS)
        if (connectivityManager?.isActiveNetworkMetered != true) {
            coverPreload?.let { context.imageLoader.enqueue(it) }
            screenshotPreload?.let { context.imageLoader.enqueue(it) }
        }
    }
}

@Composable
internal fun VndbScreenshotViewer(
    detail: VisualNovelDetail,
    image: VisualNovelImage,
    context: Context,
    onDismiss: () -> Unit,
) {
    val gallery = detail.screenshots.map { screenshot ->
        ViewerMedia(
            originalUrl = screenshot.url,
            previewUrl = screenshot.thumbnailUrl,
            title = "${detail.title} 截图",
            imageModel = managedImageRequest(
                url = screenshot.url,
                workId = detail.id,
                kind = CacheAssetKind.SCREENSHOT_ORIGINAL,
                adult = detail.adult || screenshot.sensitive,
            ),
        )
    }
    CoverViewerDialog(
        media = ViewerMedia(
            originalUrl = image.url,
            previewUrl = image.thumbnailUrl,
            title = "${detail.title} 截图",
            imageModel = gallery.firstOrNull { it.originalUrl == image.url }?.imageModel,
            gallery = gallery,
            initialIndex = detail.screenshots.indexOfFirst { it.url == image.url }.coerceAtLeast(0),
        ),
        onDismiss = onDismiss,
        onMessage = { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        },
    )
}

@Composable
internal fun VndbScreenshotSection(
    detail: VisualNovelDetail,
    revealed: Set<String>,
    onReveal: (String) -> Unit,
    onOpen: (VisualNovelImage) -> Unit,
) {
    if (detail.screenshots.isEmpty()) return
    SectionCard(title = "截图") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(detail.screenshots, key = { it.url }) { image ->
                ManagedVisualNovelImage(
                    spec = ManagedImageSpec(
                        image = image,
                        workId = detail.id,
                        kind = CacheAssetKind.SCREENSHOT_THUMBNAIL,
                        adult = detail.adult,
                    ),
                    revealed = revealed,
                    onReveal = onReveal,
                    onOpen = onOpen,
                    modifier = Modifier.size(width = 220.dp, height = 132.dp),
                )
            }
        }
        Text(
            "列表只缓存缩略图；点开大图后才缓存原图。敏感图片需单独点按显示。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun VndbOverviewSections(detail: VisualNovelDetail) {
    ExpandableVndbSection(
        title = "语言、平台与发行信息",
        body = buildString {
            append("发行：").append(detail.released ?: NO_DATA)
            append("\n语言：").append(detail.languages.ifEmpty { listOf(NO_DATA) }.joinToString(" / "))
            append("\n平台：").append(detail.platforms.ifEmpty { listOf(NO_DATA) }.joinToString(" / "))
        },
        initiallyExpanded = true,
    )
    detail.description?.takeIf(String::isNotBlank)?.let { description ->
        ExpandableVndbSection(title = "VNDB 原文简介", body = description, initiallyExpanded = false)
    }
    detail.translatedDescription?.takeIf(String::isNotBlank)?.let { translated ->
        ExpandableVndbSection(
            title = "中文简介（AI 翻译，可能不准确）",
            body = translated,
            initiallyExpanded = true,
        )
    }
}

@Composable
internal fun VndbProducerSection(
    detail: VisualNovelDetail,
    onOpenProducer: (String, String) -> Unit,
    context: android.content.Context,
) {
    if (detail.developers.isEmpty()) return
    SectionCard(title = "开发商 / 发行商") {
        detail.developers.forEach { producer ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PrismCorner.Badge)
                    .clickable { onOpenProducer(producer.id, producer.name) }
                    .padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(producer.name, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                FilledTonalButton(
                    onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://vndb.org/${producer.id}"),
                        )
                        runCatching { context.startActivity(intent) }
                    },
                ) { Text("VNDB") }
            }
        }
    }
}

@Composable
internal fun VndbCreditsSections(detail: VisualNovelDetail) {
    if (detail.staff.isNotEmpty()) {
        ExpandableVndbSection(
            title = "Staff（${detail.staff.size}）",
            body = detail.staff.joinToString("\n") { credit ->
                listOfNotNull(credit.name, credit.role, credit.note).joinToString(" · ")
            },
            initiallyExpanded = false,
        )
    }
    if (detail.voiceRoles.isNotEmpty()) {
        ExpandableVndbSection(
            title = "角色与声优（${detail.voiceRoles.size}）",
            body = detail.voiceRoles.joinToString("\n") { "${it.characterName} · ${it.actorName}" },
            initiallyExpanded = false,
        )
    }
}

@Composable
internal fun VndbRelationsSection(detail: VisualNovelDetail, onOpenWork: (String) -> Unit) {
    if (detail.relations.isEmpty()) return
    SectionCard(title = "关联作品") {
        detail.relations.forEach { relation ->
            Text(
                text = "${relation.relation} · ${relation.title}",
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(PrismCorner.Badge)
                    .clickable { onOpenWork(relation.id) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
internal fun ExpandableVndbSection(title: String, body: String, initiallyExpanded: Boolean) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    SectionCard(title = title) {
        Text(
            text = if (expanded) "收起" else "展开",
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(PrismCorner.Badge).clickable { expanded = !expanded },
        )
        if (expanded) Text(body.ifBlank { NO_DATA })
    }
}
