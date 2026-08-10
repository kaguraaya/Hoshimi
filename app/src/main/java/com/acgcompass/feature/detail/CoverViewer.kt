package com.acgcompass.feature.detail

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 详情页封面大图查看器。全屏展示封面，支持双指缩放查看，并提供「保存到相册」按钮。
 *
 * 下载经 Coil [ImageLoader] 复用缓存取回位图，再通过 [MediaStore] 写入系统相册（Pictures/ACGCompass），
 * 兼容 Android 10+ 分区存储（不需要 WRITE_EXTERNAL_STORAGE 权限）。失败时回调提示，不崩溃。
 */
@Composable
internal fun CoverViewerDialog(media: ViewerMedia, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableStateOf(1f) }
    // 放大后的平移偏移；缩放回 1 时归零，避免图片卡在偏移位置无法复位。
    var offset by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }
    val gallery = remember(media.originalUrl, media.gallery) { media.gallery.ifEmpty { listOf(media) } }
    val pagerState = rememberPagerState(
        initialPage = media.initialIndex.coerceIn(0, gallery.lastIndex),
        pageCount = { gallery.size },
    )
    val activeMedia = gallery.getOrElse(pagerState.currentPage) { gallery.first() }

    androidx.compose.runtime.LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                // 点击图片以外区域关闭（系统返回手势同样可关闭）。
                .pointerInput("dismiss") {
                    detectTapGestures(onTap = { onDismiss() })
                },
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = scale <= SWIPE_ZOOM_THRESHOLD,
                beyondViewportPageCount = 1,
            ) { page ->
                val pageMedia = gallery[page]
                ZoomableViewerImage(pageMedia, scale, offset) { pan, zoom ->
                    val nextScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = nextScale
                    offset = if (nextScale > 1f) offset + pan * nextScale else Offset.Zero
                }
            }

            GalleryNavigation(
                currentIndex = pagerState.currentPage,
                itemCount = gallery.size,
                onPrevious = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                onNext = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
            )
            SaveImageButton(saving = saving) {
                saving = true
                scope.launch {
                    val ok = saveCoverToGallery(context, activeMedia.originalUrl, activeMedia.title)
                    saving = false
                    onMessage(if (ok) "已保存到相册" else "保存失败，请稍后重试")
                }
            }
        }
    }
}

@Composable
private fun BoxScope.GalleryNavigation(currentIndex: Int, itemCount: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    if (itemCount > 1 && currentIndex > 0) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(Icons.Default.ChevronLeft, contentDescription = "上一张")
        }
    }
    if (itemCount > 1 && currentIndex < itemCount - 1) {
        IconButton(
            onClick = onNext,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(Icons.Default.ChevronRight, contentDescription = "下一张")
        }
    }
}

@Composable
private fun BoxScope.SaveImageButton(saving: Boolean, onSave: () -> Unit) {
    Button(
        enabled = !saving,
        onClick = onSave,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(24.dp),
    ) {
        Text(if (saving) "保存中…" else "保存到相册")
    }
}

internal data class ViewerMedia(
    val originalUrl: String,
    val title: String,
    val previewUrl: String? = null,
    val imageModel: Any? = null,
    val gallery: List<ViewerMedia> = emptyList(),
    val initialIndex: Int = 0,
)

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun ZoomableViewerImage(
    media: ViewerMedia,
    scale: Float,
    offset: Offset,
    onTransform: (Offset, Float) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var retryKey by remember(media.originalUrl) { mutableStateOf(0) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        onTransform(panChange, zoomChange)
    }
    SubcomposeAsyncImage(
        model = if (retryKey == 0) {
            media.imageModel ?: media.originalUrl
        } else {
            ImageRequest.Builder(context)
                .data(media.originalUrl)
                .setParameter("viewer_retry", retryKey)
                .build()
        },
        contentDescription = media.title,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            )
            .transformable(
                state = transformState,
                canPan = { scale > SWIPE_ZOOM_THRESHOLD },
            ),
        loading = { PreviewImage(media.previewUrl, media.title) },
        error = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PreviewImage(media.previewUrl, media.title)
                OutlinedButton(onClick = { retryKey++ }) { Text("重试原图") }
            }
        },
        success = { SubcomposeAsyncImageContent() },
    )
}

@Composable
private fun PreviewImage(url: String?, title: String) {
    if (url == null) return
    coil.compose.AsyncImage(
        model = url,
        contentDescription = title,
        modifier = Modifier.fillMaxSize().padding(16.dp),
    )
}

/** 经 Coil 取回封面位图并写入系统相册。返回是否成功。 */
private suspend fun saveCoverToGallery(context: Context, url: String, title: String): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context)
                .data(url)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            if (result !is SuccessResult) return@withContext false
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@withContext false

            val safeName = title.replace(Regex("[^\\p{L}\\p{N}_-]"), "_").take(40).ifBlank { "cover" }
            val fileName = "ACGCompass_${safeName}_${System.currentTimeMillis()}.jpg"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/ACGCompass",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            } ?: return@withContext false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        }.getOrDefault(false)
    }

private const val SWIPE_ZOOM_THRESHOLD = 1.02f
