package com.acgcompass.feature.visualnovel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.domain.cache.CacheAssetKind
import com.acgcompass.domain.cache.ManagedMediaRecord
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.visualnovel.VisualNovelDetail
import com.acgcompass.domain.visualnovel.VisualNovelImage

@Composable
internal fun ManagedVisualNovelImage(
    spec: ManagedImageSpec,
    revealed: Set<String>,
    onReveal: (String) -> Unit,
    onOpen: (VisualNovelImage) -> Unit,
    modifier: Modifier,
) {
    val image = spec.image ?: return
    val hidden = image.sensitive && image.url !in revealed
    var retryKey by remember(image.thumbnailUrl, image.url) { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                if (hidden) onReveal(image.url) else onOpen(image)
            },
    ) {
        SubcomposeAsyncImage(
            model = managedImageRequest(
                url = image.thumbnailUrl ?: image.url,
                workId = spec.workId,
                kind = spec.kind,
                adult = spec.adult || image.sensitive,
                retryKey = retryKey,
            ),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().then(if (hidden) Modifier.blur(18.dp) else Modifier),
            loading = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Surfaces.raisedColor),
                )
            },
            error = {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Surfaces.raisedColor),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = { retryKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "重试图片")
                    }
                }
            },
            success = { SubcomposeAsyncImageContent() },
        )
        if (hidden) Text("敏感图片 · 点按显示", modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
internal fun FullscreenImageDialog(image: VisualNovelImage, detail: VisualNovelDetail, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = managedImageRequest(
                    url = image.url,
                    workId = detail.id,
                    kind = if (image === detail.cover) {
                        CacheAssetKind.COVER
                    } else {
                        CacheAssetKind.SCREENSHOT_ORIGINAL
                    },
                    adult = detail.adult || image.sensitive,
                ),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal data class ManagedImageSpec(
    val image: VisualNovelImage?,
    val workId: String,
    val kind: CacheAssetKind,
    val adult: Boolean,
)

@Composable
internal fun managedImageRequest(
    url: String,
    workId: String,
    kind: CacheAssetKind,
    adult: Boolean,
    retryKey: Int = 0,
): ImageRequest {
    val context = LocalContext.current
    return remember(url, workId, kind, adult, retryKey) {
        ImageRequest.Builder(context)
            .data(url)
            .memoryCacheKey("vndb:${kind.name}:$url")
            .diskCacheKey("vndb:${kind.name}:$url")
            .setParameter(ManagedMediaRecord.PARAM_WORK_ID, workId)
            .setParameter(ManagedMediaRecord.PARAM_SOURCE, SourceId.VNDB.name)
            .setParameter(ManagedMediaRecord.PARAM_KIND, kind.name)
            .setParameter(ManagedMediaRecord.PARAM_ADULT, adult)
            .setParameter("acg.image.retry", retryKey)
            .build()
    }
}
