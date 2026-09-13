package com.acgcompass.app.navigation

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 悬浮底栏：点按切换、按住拖动到别的项松手切换、拖动过程中不切换。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
class DockInteractionTest {

    @get:Rule
    val compose = createComposeRule()

    private val entries = TopLevelDestination.ENTRIES
    private var current by mutableStateOf(entries.first())
    private var selections = 0

    private fun setUp(dark: Boolean = false) {
        compose.setContent {
            AcgCompassTheme(darkTheme = dark, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        AcgBottomNavigation(
                            currentDestination = current,
                            onSelect = {
                                current = it
                                selections++
                            },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** 底栏内第 [index] 项中心的屏幕坐标（底栏左右各留 20dp + 内边距 4dp，高 64dp、离底 8dp）。 */
    private fun slotCenterX(width: Float, index: Int, density: Float): Float {
        val inset = (20f + 4f) * density
        val slot = (width - 2 * inset) / entries.size
        return inset + slot * (index + 0.5f)
    }

    @Test
    fun tapSwitchesAndDragSelectsWhereReleased() {
        setUp()
        val density = compose.density.density
        compose.onRoot().performTouchInput {
            val y = height - (8f + 32f) * density
            click(androidx.compose.ui.geometry.Offset(slotCenterX(width.toFloat(), 2, density), y))
        }
        compose.waitForIdle()
        assertEquals(entries[2], current)

        val before = selections
        compose.onRoot().performTouchInput {
            val y = height - (8f + 32f) * density
            down(androidx.compose.ui.geometry.Offset(slotCenterX(width.toFloat(), 2, density), y))
            moveTo(androidx.compose.ui.geometry.Offset(slotCenterX(width.toFloat(), 3, density), y))
            moveTo(androidx.compose.ui.geometry.Offset(slotCenterX(width.toFloat(), 4, density), y))
        }
        compose.waitForIdle()
        // 还按着：只是镜片在走，页面不切换。
        assertEquals(entries[2], current)
        assertEquals(before, selections)
        compose.mainClock.advanceTimeBy(400)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/dock-dragging.png")
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
        assertEquals(entries[4], current)
        assertEquals(before + 1, selections)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/dock-settled.png")
    }
}
