package com.acgcompass.domain.tastecode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TasteCodeCodecTest {
    private fun sample(nickname: String? = "星见") = TasteCode(
        generatedOnDay = 20_776,
        algorithmVersion = 7,
        nickname = nickname,
        style = ScoringStyle(ratedCount = 312, mean = 7.35f, spread = 1.4f, leniency = -0.4f, followsCrowd = 0.82f),
        dimensions = mapOf("topic" to 1f, "xp" to 0.42f),
        liked = List(TasteCode.MAX_LIKED) { WeightedTag("标签$it", 1f - it / 30f) },
        avoided = listOf(WeightedTag("后宫", 1f), WeightedTag("致郁", 0.5f)),
        divergence = listOf(WeightedTag("百合", 0.65f), WeightedTag("热血", -0.4f)),
        eraShares = listOf(0f, 0.05f, 0.1f, 0.1f, 0.2f, 0.25f, 0.25f, 0.05f),
        works = List(TasteCode.MAX_WORKS) {
            RepresentativeWork(100_000 + it * 7919, 10 - it % 10, RepresentativeReason.entries[it % 3])
        },
    )

    @Test
    fun roundTripKeepsEveryFieldWithinQuantization() {
        val code = sample()
        val text = TasteCodeCodec.encode(code)
        assertTrue(text.startsWith(TasteCodeCodec.PREFIX))
        val decoded = TasteCodeCodec.decode(text)!!
        assertEquals(code.nickname, decoded.nickname)
        assertEquals(code.generatedOnDay, decoded.generatedOnDay)
        assertEquals(code.algorithmVersion, decoded.algorithmVersion)
        assertEquals(code.style.ratedCount, decoded.style.ratedCount)
        assertEquals(code.style.mean, decoded.style.mean, 0.05f)
        assertEquals(code.style.leniency, decoded.style.leniency, 0.05f)
        assertEquals(code.style.followsCrowd, decoded.style.followsCrowd, 0.02f)
        assertEquals(code.dimensions.keys, decoded.dimensions.keys)
        assertEquals(code.liked.map { it.tag }, decoded.liked.map { it.tag })
        assertEquals(code.divergence[1].weight, decoded.divergence[1].weight, 0.05f)
        assertEquals(code.eraShares.size, decoded.eraShares.size)
        assertEquals(code.works, decoded.works)
    }

    @Test
    fun fullCodeFitsComfortablyInAQrCode() {
        val text = TasteCodeCodec.encode(sample())
        assertTrue("length ${text.length}", text.length < 900)
    }

    @Test
    fun nicknameIsOptional() {
        assertNull(TasteCodeCodec.decode(TasteCodeCodec.encode(sample(nickname = null)))!!.nickname)
    }

    @Test
    fun extractsCodeFromLinkOrShareText() {
        val text = TasteCodeCodec.encode(sample())
        assertNotNull(TasteCodeCodec.decode(TasteCodeCodec.LINK_PREFIX + text))
        assertNotNull(TasteCodeCodec.decode("来看看我的口味：$text 用 Hoshimi 打开"))
    }

    @Test
    fun corruptedOrTruncatedCodeIsRejected() {
        val text = TasteCodeCodec.encode(sample())
        assertNull(TasteCodeCodec.decode(text.dropLast(12)))
        val flipped = text.substring(0, 20) + (if (text[20] == 'A') 'B' else 'A') + text.substring(21)
        assertNull(TasteCodeCodec.decode(flipped))
        assertNull(TasteCodeCodec.decode("HSM1.not-a-real-code"))
        assertNull(TasteCodeCodec.decode("随便一段文字"))
    }

    @Test
    fun tagCategoriesSurviveRoundTrip() {
        val code = sample().copy(
            liked = listOf(WeightedTag("百合", 1f, "topic"), WeightedTag("花泽香菜", 0.5f, "cv"), WeightedTag("未分类", 0.2f)),
        )
        val decoded = TasteCodeCodec.decode(TasteCodeCodec.encode(code))!!
        assertEquals(listOf("topic", "cv", null), decoded.liked.map { it.category })
    }
}
