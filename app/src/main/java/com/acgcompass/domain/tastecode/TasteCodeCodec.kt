package com.acgcompass.domain.tastecode

import com.acgcompass.domain.taste.TasteCategory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.math.roundToInt

/**
 * 口味码的文本格式：`HSM1.` + Base64url（无填充）。负载是紧凑的二进制（数值量化成单字节），先附 16 位校验、再用
 * Deflate 压缩。带 30 部代表作时大约 300–500 字节、编码后五六百个字符，做成二维码手机能扫。
 *
 * 也接受链接形式 `hoshimi://taste/HSM1.…` 和夹在一段话里的口味码（[extract]）。
 */
@Suppress("TooManyFunctions") // 二进制读写按字段类型各一对小函数
object TasteCodeCodec {
    const val PREFIX = "HSM1."
    const val LINK_PREFIX = "hoshimi://taste/"
    private val MAGIC = byteArrayOf('H'.code.toByte(), 'S'.code.toByte(), 'M'.code.toByte())
    private const val FORMAT_VERSION = 1
    private const val FLAG_NICKNAME = 1
    private const val MAX_INFLATED = 16 * 1024
    private val CODE_PATTERN = Regex("HSM1\\.[A-Za-z0-9_-]+")

    /** 编码成文本口味码。 */
    fun encode(code: TasteCode): String {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).use { out -> writeBody(out, code) }
        val body = raw.toByteArray()
        val crc = CRC32().apply { update(body) }.value.toInt()
        val withCrc = body + byteArrayOf((crc ushr BYTE_BITS).toByte(), crc.toByte())
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(deflate(withCrc))
    }

    /** 编码成可以点开的链接。 */
    fun link(code: TasteCode): String = LINK_PREFIX + encode(code)

    /** 从一段文字（口味码、链接或包含它们的分享文案）里找出口味码文本；找不到返回 null。 */
    fun extract(text: String): String? = CODE_PATTERN.find(text.trim())?.value

    /** 解析口味码；格式不对、校验不通过或被截断时返回 null。 */
    fun decode(text: String): TasteCode? = runCatching {
        val token = extract(text) ?: return null
        val packed = Base64.getUrlDecoder().decode(token.removePrefix(PREFIX))
        val bytes = inflate(packed) ?: return null
        if (bytes.size < MIN_LENGTH) return null
        val body = bytes.copyOfRange(0, bytes.size - 2)
        val crc = CRC32().apply { update(body) }.value.toInt()
        val expectedHigh = (crc ushr BYTE_BITS).toByte()
        val expectedLow = crc.toByte()
        if (bytes[bytes.size - 2] != expectedHigh || bytes[bytes.size - 1] != expectedLow) return null
        DataInputStream(ByteArrayInputStream(body)).use { readBody(it) }
    }.getOrNull()

    private fun writeBody(out: DataOutputStream, code: TasteCode) {
        out.write(MAGIC)
        out.writeByte(FORMAT_VERSION)
        writeVarInt(out, code.algorithmVersion)
        writeVarInt(out, code.generatedOnDay.toInt())
        val nickname = code.nickname?.trim()?.take(TasteCode.MAX_NICKNAME)?.takeIf { it.isNotEmpty() }
        out.writeByte(if (nickname != null) FLAG_NICKNAME else 0)
        nickname?.let { writeString(out, it) }
        writeStyle(out, code.style)
        val dimensions = code.dimensions.entries.take(UBYTE_MAX)
        out.writeByte(dimensions.size)
        dimensions.forEach { (key, value) ->
            writeString(out, key)
            out.writeByte(unit(value))
        }
        writeTags(out, code.liked.take(TasteCode.MAX_LIKED)) { unit(it) }
        writeTags(out, code.avoided.take(TasteCode.MAX_AVOIDED)) { unit(it) }
        writeTags(out, code.divergence.take(TasteCode.MAX_DIVERGENCE)) { signedTwentieth(it) }
        out.writeByte(code.eraShares.size.coerceAtMost(UBYTE_MAX))
        code.eraShares.take(UBYTE_MAX).forEach { out.writeByte(unit(it)) }
        val works = code.works.take(TasteCode.MAX_WORKS)
        out.writeByte(works.size)
        works.forEach { work ->
            writeVarInt(out, work.subjectId)
            out.writeByte(work.rating.coerceIn(0, MAX_RATING))
            out.writeByte(work.reason.ordinal)
        }
    }

    private fun readBody(input: DataInputStream): TasteCode? {
        val magic = ByteArray(MAGIC.size).also { input.readFully(it) }
        if (!magic.contentEquals(MAGIC) || input.readUnsignedByte() != FORMAT_VERSION) return null
        val algorithmVersion = readVarInt(input)
        val day = readVarInt(input).toLong()
        val flags = input.readUnsignedByte()
        val nickname = if (flags and FLAG_NICKNAME != 0) readString(input) else null
        val style = readStyle(input)
        val dimensions = (0 until input.readUnsignedByte()).associate {
            readString(input) to fromUnit(input.readUnsignedByte())
        }
        val liked = readTags(input) { fromUnit(it.readUnsignedByte()) }
        val avoided = readTags(input) { fromUnit(it.readUnsignedByte()) }
        val divergence = readTags(input) { it.readByte() / TWENTIETHS }
        val eras = (0 until input.readUnsignedByte()).map { fromUnit(input.readUnsignedByte()) }
        val works = (0 until input.readUnsignedByte()).map {
            val id = readVarInt(input)
            val rating = input.readUnsignedByte()
            val reason = RepresentativeReason.entries.getOrElse(
                input.readUnsignedByte(),
            ) { RepresentativeReason.TOP_RATED }
            RepresentativeWork(id, rating, reason)
        }
        return TasteCode(day, algorithmVersion, nickname, style, dimensions, liked, avoided, divergence, eras, works)
    }

    private fun writeStyle(out: DataOutputStream, style: ScoringStyle) {
        writeVarInt(out, style.ratedCount.coerceAtLeast(0))
        out.writeByte((style.mean * TWENTIETHS).roundToInt().coerceIn(0, UBYTE_MAX))
        out.writeByte((style.spread * TWENTIETHS).roundToInt().coerceIn(0, UBYTE_MAX))
        out.writeByte(signedTwentieth(style.leniency))
        out.writeByte((style.followsCrowd * FIFTIETHS).roundToInt().coerceIn(0, UBYTE_MAX))
    }

    private fun readStyle(input: DataInputStream) = ScoringStyle(
        ratedCount = readVarInt(input),
        mean = input.readUnsignedByte() / TWENTIETHS,
        spread = input.readUnsignedByte() / TWENTIETHS,
        leniency = input.readByte() / TWENTIETHS,
        followsCrowd = input.readUnsignedByte() / FIFTIETHS,
    )

    private fun writeTags(out: DataOutputStream, tags: List<WeightedTag>, quantize: (Float) -> Int) {
        out.writeByte(tags.size)
        tags.forEach { tag ->
            writeString(out, tag.tag)
            out.writeByte(quantize(tag.weight))
            out.writeByte(CATEGORY_KEYS.indexOf(tag.category).takeIf { it >= 0 } ?: NO_CATEGORY)
        }
    }

    private fun readTags(input: DataInputStream, weight: (DataInputStream) -> Float): List<WeightedTag> =
        (0 until input.readUnsignedByte()).map {
            val tag = readString(input)
            val value = weight(input)
            WeightedTag(tag, value, CATEGORY_KEYS.getOrNull(input.readUnsignedByte()))
        }

    /** 标签分类按 [TasteCategory] 的顺序编号；只追加不改序，旧版本不认识的编号当作没有分类。 */
    private val CATEGORY_KEYS: List<String> = TasteCategory.entries.map { it.key }
    private const val NO_CATEGORY = 0xFF

    private fun writeString(out: DataOutputStream, value: String) {
        var bytes = value.toByteArray(Charsets.UTF_8)
        var cut = value
        while (bytes.size > UBYTE_MAX) {
            cut = cut.dropLast(1)
            bytes = cut.toByteArray(Charsets.UTF_8)
        }
        out.writeByte(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val bytes = ByteArray(input.readUnsignedByte()).also { input.readFully(it) }
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (v and VARINT_MASK.inv() != 0) {
            out.writeByte((v and VARINT_MASK) or VARINT_MORE)
            v = v ushr VARINT_SHIFT
        }
        out.writeByte(v)
    }

    private fun readVarInt(input: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (shift < Int.SIZE_BITS) {
            val b = input.readUnsignedByte()
            result = result or ((b and VARINT_MASK) shl shift)
            if (b and VARINT_MORE == 0) return result
            shift += VARINT_SHIFT
        }
        error("varint too long")
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(bytes: ByteArray): ByteArray? {
        val inflater = Inflater(true)
        inflater.setInput(bytes)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_INFLATED) return null
            }
        } finally {
            inflater.end()
        }
        return if (inflater.finished() || out.size() > 0) out.toByteArray() else null
    }

    private fun unit(value: Float): Int = (value.coerceIn(0f, 1f) * UBYTE_MAX).roundToInt()
    private fun fromUnit(value: Int): Float = value / UBYTE_MAX.toFloat()
    private fun signedTwentieth(value: Float): Int =
        (value * TWENTIETHS).roundToInt().coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt())

    private const val UBYTE_MAX = 255
    private const val BYTE_BITS = 8
    private const val MAX_RATING = 10
    private const val TWENTIETHS = 20f
    private const val FIFTIETHS = 50f
    private const val VARINT_MASK = 0x7F
    private const val VARINT_MORE = 0x80
    private const val VARINT_SHIFT = 7
    private const val BUFFER = 1024

    /** 魔数 + 格式版本 + 两字节校验。 */
    private const val MIN_LENGTH = 6
}
