package com.acgcompass.feature.tastecode

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 口味码的二维码：本地生成，也能从相册里的截图识别（用 ZXing，不需要相机）。 */
object QrCodes {
    private const val DARK = 0xFF000000.toInt()
    private const val LIGHT = 0xFFFFFFFF.toInt()
    private const val MAX_DECODE_SIDE = 1600

    /** 生成 [size]×[size] 像素的二维码（黑码白底，留 1 格边）。 */
    fun encode(text: String, size: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) DARK else LIGHT }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** 识别图片里的二维码文字；没有二维码或识别失败返回 null。大图先缩小，识别更快。 */
    fun decode(bitmap: Bitmap): String? = runCatching {
        val scaled = scaleDown(bitmap)
        val pixels = IntArray(scaled.width * scaled.height)
        scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
        val source = RGBLuminanceSource(scaled.width, scaled.height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    }.getOrNull()

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_DECODE_SIDE) return bitmap
        val ratio = MAX_DECODE_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt(), (bitmap.height * ratio).toInt(), true)
    }
}
