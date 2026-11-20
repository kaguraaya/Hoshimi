package com.acgcompass.feature.tasks

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.model.BackgroundTaskStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 设置 → 后台任务：浅色 / 深色各出一张图，只供人工检查。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h1600dp-420dpi")
class BackgroundTasksScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_763_550_000_000L
    private val minute = 60_000L

    private val sample = listOf(
        BackgroundTaskStatus(
            BackgroundTaskKind.SYNC,
            lastSuccessAt = now - 25 * minute,
            lastSummary = "共 75 部，新增 0、更新 2",
            nextRunAt = now + 95 * minute,
        ),
        BackgroundTaskStatus(BackgroundTaskKind.OUTBOX, lastSuccessAt = now - 3 * minute, lastSummary = "推送了 1 条"),
        BackgroundTaskStatus(BackgroundTaskKind.FEATURE_ENRICHMENT, running = true, lastSuccessAt = now - 40 * minute),
        BackgroundTaskStatus(
            BackgroundTaskKind.RATING_BACKFILL,
            lastSuccessAt = now - 6 * 60 * minute,
            lastFailureAt = now - 8 * minute,
            lastError = "请求太频繁被限速，稍后会自动再试",
            pendingNote = "4 部上次没查成，会自动再试",
            nextRunAt = now + 52 * minute,
        ),
        BackgroundTaskStatus(BackgroundTaskKind.CACHE_FILL, disabled = true, retryable = false),
        BackgroundTaskStatus(BackgroundTaskKind.POOL_EXPAND, retryable = false),
        BackgroundTaskStatus(BackgroundTaskKind.AUTO_BACKUP, lastSuccessAt = now - 2 * 24 * 60 * minute),
        BackgroundTaskStatus(
            BackgroundTaskKind.DETAIL_EXTRAS,
            lastFailureAt = now - 30 * minute,
            lastError = "条目 469212 的评论或角色没拉全：网络连不上或超时（Network）",
            retryable = false,
        ),
    )

    @Test
    fun light() = capture(dark = false, name = "background-tasks-light")

    @Test
    fun dark() = capture(dark = true, name = "background-tasks-dark")

    private fun capture(dark: Boolean, name: String) {
        compose.setContent {
            AcgCompassTheme(darkTheme = dark, dynamicColor = false) {
                BackgroundTasksScreen(
                    statuses = sample,
                    now = now,
                    snackbarHostState = SnackbarHostState(),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }
}
