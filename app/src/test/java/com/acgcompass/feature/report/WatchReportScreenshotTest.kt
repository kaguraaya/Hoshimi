package com.acgcompass.feature.report

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.domain.report.ReportPeriod
import com.acgcompass.domain.report.ReportWork
import com.acgcompass.domain.report.WatchReportBuilder
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 回顾页和各版式、各尺寸分享图的截图，只出图供人工检查。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
class WatchReportScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val report = WatchReportBuilder.build(
        ReportPeriod(2026, 4),
        (1..14).map { i ->
            ReportWork(
                "$i", "示例作品 $i 号", null, (10 - i % 6), 6.5 + i % 4 * 0.5, 2020 + i % 7, 12, null,
                finishedIn(10 + i % 3),
                listOf(
                    "日常",
                    "科幻",
                    "百合",
                ).take(1 + i % 3),
            )
        },
        dropped = 1,
    )

    @Test
    fun reportPage() {
        compose.setContent {
            AcgCompassTheme(darkTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    WatchReportContent(
                        state = WatchReportUiState(
                            loading = false,
                            report = report,
                            style = ReportStyle(hidden = setOf("3")),
                        ),
                        onSelectPeriod = {},
                        onSelectTemplate = {},
                        onSelectSize = {},
                        onToggleHideRatings = {},
                        onToggleHidden = {},
                        onOpenWork = {},
                        contentPadding = PaddingValues(16.dp),
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/watch-report.png")
    }

    @Test
    fun shareCard() {
        compose.setContent {
            AcgCompassTheme(darkTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    ShareCard(
                        state = WatchReportUiState(
                            loading = false,
                            report = report,
                            style = ReportStyle(ReportTemplate.POSTER),
                        ),
                        onSelectTemplate = {},
                        onSelectSize = {},
                        onToggleHideRatings = {},
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription("分享图预览").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/watch-report-share-card.png")
    }

    @Test
    fun shareImages() {
        val covers = report.finished.associate { it.workId to fakeCover(it.workId.toInt()) }
        ReportSize.entries.forEach { size ->
            ReportTemplate.entries.forEach { template ->
                val image = ReportImage.render(report, ReportStyle(template, size), covers)
                val name = "watch-report-${template.name.lowercase()}-${size.name.lowercase()}.png"
                File("build/outputs/roborazzi/$name").apply { parentFile?.mkdirs() }
                    .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }

    /** 一年的回顾：作品分散在几个月里，有的月份空着。 */
    @Test
    fun yearTimeline() {
        val year = WatchReportBuilder.build(
            ReportPeriod(2026),
            report.finished.mapIndexed { i, w -> w.copy(finishedAt = finishedIn(1 + i * 5 % 12)) } +
                (20..31).map { i -> report.finished[0].copy(workId = "$i", finishedAt = finishedIn(3)) },
            dropped = 0,
        )
        val image = ReportImage.render(year, ReportStyle(ReportTemplate.TIMELINE), emptyMap())
        File("build/outputs/roborazzi/watch-report-timeline-year.png").apply { parentFile?.mkdirs() }
            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun finishedIn(month: Int): Long =
        LocalDate.of(2026, month, 15).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 带渐变色的假封面，看裁切和取色。 */
    private fun fakeCover(seed: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(300, 420, Bitmap.Config.ARGB_8888)
        val hue = seed * 47f % 360f
        Canvas(bitmap).drawPaint(
            Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 300f, 420f,
                    Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.9f)),
                    Color.HSVToColor(floatArrayOf((hue + 60f) % 360f, 0.7f, 0.5f)),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        return bitmap
    }
}
