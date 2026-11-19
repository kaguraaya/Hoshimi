package com.acgcompass.feature.discover

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 发现 → 筛选：收起和展开两种状态的截图，只出图供人工检查。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
class DiscoverFilterScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun filterSection() {
        compose.setContent {
            AcgCompassTheme(darkTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    FilterSection(
                        filter = DiscoverFilter(genres = setOf("日常")),
                        facets = FilterFacets(
                            years = (2015..2026).toList().reversed(),
                            lengths = listOf("短篇", "单季", "长篇"),
                            genres = COMMON_GENRES,
                        ),
                        results = (1..8).map { i ->
                            RankedWork("$i", WorkCardUiModel(null, "示例作品 $i", "2024", "动画", "Bangumi 7.5"))
                        },
                        onFilterChange = {},
                        onClearFilter = {},
                        onOpenWork = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/discover-filter-collapsed.png")
        compose.onNodeWithText("筛选 · 1").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/discover-filter-expanded.png")
    }
}
