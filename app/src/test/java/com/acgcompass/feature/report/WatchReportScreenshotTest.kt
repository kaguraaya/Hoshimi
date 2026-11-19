package com.acgcompass.feature.report

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.domain.report.ReportPeriod
import com.acgcompass.domain.report.ReportWork
import com.acgcompass.domain.report.WatchReportBuilder
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 回顾页和两种分享图的截图，只出图供人工检查。 */
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
                "$i", "示例作品 $i 号", null, (10 - i % 6), 6.5 + i % 4 * 0.5, 2020 + i % 7, 12, null, 0L,
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
                        state = WatchReportUiState(loading = false, report = report),
                        onSelectPeriod = {},
                        onSelectTemplate = {},
                        onToggleHideRatings = {},
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
    fun shareImages() {
        ReportTemplate.entries.forEach { template ->
            val image = ReportImage.render(report, template, emptyMap(), hideRatings = false)
            File("build/outputs/roborazzi/watch-report-${template.name.lowercase()}.png").apply { parentFile?.mkdirs() }
                .outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
