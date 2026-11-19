package com.acgcompass.feature.tastecode

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.domain.tastecode.RepresentativeReason
import com.acgcompass.domain.tastecode.RepresentativeWork
import com.acgcompass.domain.tastecode.ScoringStyle
import com.acgcompass.domain.tastecode.TasteCode
import com.acgcompass.domain.tastecode.TasteCodeCodec
import com.acgcompass.domain.tastecode.TasteCodeWorkInfo
import com.acgcompass.domain.tastecode.TasteCompatibility
import com.acgcompass.domain.tastecode.WeightedTag
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 口味相性页和口味码图片的截图，只出图供人工检查。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
class TasteCodeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Test
    @Suppress("LongMethod") // 两份示例口味码
    fun compatibilityPage() {
        val mine = sampleCode(
            nickname = null,
            liked = listOf(
                WeightedTag("百合", 1f, "topic"),
                WeightedTag("日常", 0.9f, "topic"),
                WeightedTag("音乐", 0.8f, "topic"),
                WeightedTag("黑长直", 0.6f, "xp"),
                WeightedTag("京都动画", 0.7f, "staff"),
                WeightedTag("悬疑", 0.5f, "topic"),
            ),
            avoided = listOf(WeightedTag("后宫", 1f, "xp"), WeightedTag("血腥", 0.6f, "topic")),
            dims = mapOf(
                "topic" to 1f,
                "combo" to 0.7f,
                "device" to 0.4f,
                "xp" to 0.5f,
                "character" to 0.2f,
                "staff" to 0.6f,
                "cv" to 0.3f,
                "source" to 0.4f,
            ),
            style = ScoringStyle(320, 7.4f, 1.3f, 0.5f, 0.8f),
            eras = listOf(0f, 0.02f, 0.08f, 0.2f, 0.3f, 0.25f, 0.12f, 0.03f),
            works = listOf(w(1, 10), w(2, 9), w(3, 8), w(4, 9), w(5, 6)),
        )
        val other = sampleCode(
            nickname = "小林",
            liked = listOf(
                WeightedTag("百合", 0.8f, "topic"),
                WeightedTag("日常", 1f, "topic"),
                WeightedTag("后宫", 0.7f, "xp"),
                WeightedTag("热血", 0.9f, "topic"),
                WeightedTag("京都动画", 0.5f, "staff"),
                WeightedTag("花泽香菜", 0.6f, "cv"),
            ),
            avoided = listOf(WeightedTag("血腥", 1f, "topic"), WeightedTag("音乐", 0.4f, "topic")),
            dims = mapOf(
                "topic" to 0.8f,
                "combo" to 0.5f,
                "device" to 0.7f,
                "xp" to 1f,
                "character" to 0.6f,
                "staff" to 0.3f,
                "cv" to 0.8f,
                "source" to 0.2f,
            ),
            style = ScoringStyle(85, 6.2f, 2.1f, -0.4f, 1.3f),
            eras = listOf(0f, 0f, 0f, 0.02f, 0.08f, 0.3f, 0.45f, 0.15f),
            works = listOf(w(1, 9), w(2, 5), w(3, 8), w(6, 9), w(7, 8)),
        )
        val result = TasteCompatibility.compare(mine, other)
        val info = (1..7).associateWith { TasteCodeWorkInfo(subjectId = it, title = "示例作品 $it", coverUrl = null) }
        val state = CompatibilityUiState.Ready(
            otherName = "小林",
            mine = mine,
            other = other,
            result = result,
            insights = CompatibilityInsights.of(mine, other, result, "小林"),
            picks = result.picksForA.map { RepresentativeItem(it, info[it.subjectId]?.title, null) },
            works = info,
        )
        compose.setContent {
            AcgCompassTheme(darkTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    TasteCompatibilityScreen(state = state, onBack = {}, onOpenWork = {})
                }
            }
        }
        compose.waitForIdle()
        shot("taste-compat-1")
        listOf("口味维度对比", "你们俩", "喜好与雷点", "都放进代表作的 3 部").forEachIndexed { index, text ->
            compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
            compose.waitForIdle()
            shot("taste-compat-${index + 2}")
        }
    }

    @Test
    fun selfCompareDimensions() {
        val mine = sampleCode(
            nickname = null,
            liked = listOf(WeightedTag("百合", 1f, "topic")),
            avoided = emptyList(),
            dims = mapOf(
                "topic" to 1f,
                "combo" to 0.8f,
                "device" to 0.5f,
                "xp" to 0.6f,
                "character" to 0.3f,
                "staff" to 0.4f,
                "cv" to 0.2f,
                "source" to 0.5f,
            ),
            style = ScoringStyle(200, 7f, 1.5f, 0f, 1f),
            eras = listOf(0f, 0f, 0.1f, 0.2f, 0.3f, 0.2f, 0.2f, 0f),
            works = listOf(w(1, 9), w(2, 8), w(3, 7)),
        )
        val result = TasteCompatibility.compare(mine, mine)
        val state = CompatibilityUiState.Ready(
            "Ta",
            mine,
            mine,
            result,
            CompatibilityInsights.of(mine, mine, result, "Ta"),
            emptyList(),
            emptyMap(),
        )
        compose.setContent {
            AcgCompassTheme(darkTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    TasteCompatibilityScreen(state = state, onBack = {}, onOpenWork = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("口味维度对比"))
        compose.waitForIdle()
        shot("taste-compat-self")
    }

    @Test
    fun savedQrImage() {
        val code = TasteCodeCodec.encode(
            sampleCode(
                "小林",
                emptyList(),
                emptyList(),
                emptyMap(),
                ScoringStyle(10, 7f, 1f, 0f, 1f),
                emptyList(),
                emptyList(),
            ),
        )
        val image = TasteCodeImage.render(code, "小林")
        val out = File("build/outputs/roborazzi/taste-code-image.png").apply { parentFile?.mkdirs() }
        out.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        check(QrCodes.decode(image) == code) { "saved image should decode back to the code" }
    }

    @Suppress("LongParameterList")
    private fun sampleCode(
        nickname: String?,
        liked: List<WeightedTag>,
        avoided: List<WeightedTag>,
        dims: Map<String, Float>,
        style: ScoringStyle,
        eras: List<Float>,
        works: List<RepresentativeWork>,
    ) = TasteCode(
        generatedOnDay = 20_000,
        algorithmVersion = 1,
        nickname = nickname,
        style = style,
        dimensions = dims,
        liked = liked,
        avoided = avoided,
        divergence = listOf(WeightedTag("百合", 0.5f)),
        eraShares = eras,
        works = works,
    )

    private fun w(id: Int, rating: Int) = RepresentativeWork(id, rating, RepresentativeReason.TOP_RATED)
}
