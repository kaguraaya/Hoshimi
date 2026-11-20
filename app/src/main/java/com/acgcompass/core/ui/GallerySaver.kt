package com.acgcompass.core.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.acgcompass.core.common.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 把图片存进系统相册（Pictures/Hoshimi）。Android 10 起不需要存储权限。 */
object GallerySaver {
    private const val ALBUM = "Hoshimi"
    private const val PNG_QUALITY = 100

    /** 以 PNG 保存 [bitmap]，文件名前缀为 [name]。返回是否成功。 */
    suspend fun savePng(context: Context, bitmap: Bitmap, name: String): Boolean =
        savePngUri(context, bitmap, name) != null

    /** 以 PNG 保存 [bitmap]，返回相册里的地址（可以直接交给系统分享）；失败返回 null。 */
    suspend fun savePngUri(context: Context, bitmap: Bitmap, name: String): Uri? = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${name}_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatchingCancellable null
            val written = resolver.openOutputStream(
                uri,
            )?.use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
            if (written != true) {
                resolver.delete(uri, null, null)
                return@runCatchingCancellable null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            uri
        }.getOrNull()
    }
}
