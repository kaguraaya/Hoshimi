package com.acgcompass.feature.tastecode

import android.app.Application
import com.acgcompass.domain.tastecode.RepresentativeReason
import com.acgcompass.domain.tastecode.RepresentativeWork
import com.acgcompass.domain.tastecode.ScoringStyle
import com.acgcompass.domain.tastecode.TasteCode
import com.acgcompass.domain.tastecode.TasteCodeCodec
import com.acgcompass.domain.tastecode.WeightedTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class QrCodesTest {
    @Test
    fun fullTasteCodeSurvivesQrRoundTrip() {
        val code = TasteCode(
            generatedOnDay = 20_776,
            algorithmVersion = 7,
            nickname = "星见",
            style = ScoringStyle(312, 7.3f, 1.4f, -0.4f, 0.8f),
            liked = List(TasteCode.MAX_LIKED) { WeightedTag("标签$it", 1f) },
            avoided = List(TasteCode.MAX_AVOIDED) { WeightedTag("雷点$it", 1f) },
            works = List(
                TasteCode.MAX_WORKS,
            ) { RepresentativeWork(300_000 + it * 97, 8, RepresentativeReason.TOP_RATED) },
        )
        val text = TasteCodeCodec.encode(code)
        val decoded = QrCodes.decode(QrCodes.encode(text, 720))
        assertEquals(text, decoded)
        assertNotNull(TasteCodeCodec.decode(decoded!!))
    }
}
