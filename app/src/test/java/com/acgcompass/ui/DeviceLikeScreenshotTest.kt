package com.acgcompass.ui

import android.app.Application
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.AcgShapes
import com.acgcompass.core.designsystem.AcgTypography
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.cache.CachePolicy
import com.acgcompass.domain.model.ImportItem
import com.acgcompass.domain.model.ImportItemStatus
import com.acgcompass.feature.cache.CacheManagementUiState
import com.acgcompass.feature.cache.PolicyCard
import com.acgcompass.feature.home.DailyDraw
import com.acgcompass.feature.home.HomeScreen
import com.acgcompass.feature.home.HomeUiState
import com.acgcompass.feature.home.SyncReminder
import com.acgcompass.feature.home.SyncReminderLine
import com.acgcompass.feature.imports.ImportScreen
import com.acgcompass.feature.imports.ImportUiState
import com.acgcompass.feature.recommender.RecommenderInput
import com.acgcompass.feature.recommender.RecommenderScreen
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 按「最坏情况的真机」渲染：壁纸取色得到的高饱和青色配色 + 放大的字宽（模拟加粗的自定义系统字体）。
 *
 * 默认走查（[ScreenWalkthroughTest]）用的是品牌配色和默认字体，照不出这两类问题：
 * 卡片在动态取色下变成一整块重色、等宽控件里的文字被截断。这里只出图供人工检查，不做像素断言。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
class DeviceLikeScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    private fun render(dark: Boolean, content: @Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(WIDE_FONT_SCALE)
        compose.setContent {
            DeviceLikeTheme(
                dark,
            ) { Surface(color = MaterialTheme.colorScheme.background) { content() } }
        }
        compose.waitForIdle()
    }

    @Test
    fun homeDark() {
        render(dark = true) { SampleHome() }
        shot("device-home-dark")
    }

    @Test
    fun homeLight() {
        render(dark = false) { SampleHome() }
        shot("device-home-light")
    }

    @Test
    fun tonightLight() {
        render(dark = false) { SampleTonight() }
        shot("device-tonight-light")
    }

    @Test
    fun tonightDark() {
        render(dark = true) { SampleTonight() }
        shot("device-tonight-dark")
    }

    @Test
    fun importLight() {
        render(dark = false) { SampleImport() }
        shot("device-import-light")
    }

    @Test
    fun cachePolicyLight() {
        render(dark = false) {
            androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                PolicyCard(
                    state = CacheManagementUiState(policy = CachePolicy(backgroundFillEnabled = true)),
                    onEvent = {},
                )
            }
        }
        shot("device-cache-policy-light")
    }

    private companion object {
        const val WIDE_FONT_SCALE = 1.15f
    }
}

@Composable
private fun SampleHome() {
    HomeScreen(
        state = UiState.Success(
            HomeUiState(
                syncReminder = SyncReminder(
                    lines = listOf(SyncReminderLine("Bangumi", "最近同步 · 刚刚", failed = false)),
                    hasFailure = false,
                ),
                dailyDraw = DailyDraw(
                    shouldText = "补一部短篇，今晚就能看完",
                    shouldNotText = "忌挑超长篇硬啃，今晚时间不够",
                ),
            ),
        ),
        onManualSync = {},
        onOpenRecommender = {},
        onOpenSearch = {},
        onOpenImport = {},
        onOpenWork = {},
    )
}

@Composable
private fun SampleTonight() {
    RecommenderScreen(
        input = RecommenderInput(selectedTags = setOf("日常")),
        result = UiState.Empty(Cta("选择条件后生成推荐", "submit")),
        availableTags = listOf("校园", "恋爱", "日常", "搞笑", "催泪", "悬疑", "科幻"),
        onSelectTime = {},
        onToggleTag = {},
        onToggleAcceptance = {},
        onToggleIndecision = {},
        onToggleFinalsProtection = {},
        onToggleLateNight = {},
        onSelectCandidatePool = {},
        onSubmit = {},
        onOpenWork = {},
        onBack = {},
    )
}

@Composable
private fun SampleImport() {
    fun item(id: String, title: String, status: ImportItemStatus, confidence: Float) = ImportItem(
        id = id,
        batchId = "b1",
        rawText = title,
        parsedTitle = title,
        workId = id.takeIf { status != ImportItemStatus.UNMATCHED },
        matchConfidence = confidence,
        status = status,
    )
    ImportScreen(
        state = ImportUiState(
            pastedText = "葬送的芙莉莲\n孤独摇滚！\n比宇宙更远的地方\n某部查不到的作品",
            previewTitles = listOf("葬送的芙莉莲", "孤独摇滚！", "比宇宙更远的地方", "某部查不到的作品"),
        ),
        items = listOf(
            item("1", "葬送的芙莉莲", ImportItemStatus.MATCHED, 0.96f),
            item("2", "孤独摇滚！", ImportItemStatus.ADDED, 0.93f),
            item("3", "比宇宙更远的地方", ImportItemStatus.NEEDS_CONFIRMATION, 0.61f),
            item("4", "某部查不到的作品", ImportItemStatus.UNMATCHED, 0f),
        ),
        onBack = {},
        onTextChange = {},
        onParse = {},
        onAddToBacklog = {},
    )
}

/** 近似用户设备上壁纸取色得到的青色配色（浅色页面底偏青、默认卡片色很重；深色主容器是亮青）。 */
@Composable
private fun DeviceLikeTheme(dark: Boolean, content: @Composable () -> Unit) {
    val scheme = if (dark) cyanDark() else cyanLight()
    MaterialTheme(
        colorScheme = scheme.copy(surfaceContainerHighest = scheme.surfaceContainer),
        typography = AcgTypography,
        shapes = AcgShapes,
        content = content,
    )
}

@Suppress("MagicNumber") // 调色板字面量
private fun cyanLight(): ColorScheme = lightColorScheme(
    primary = Color(0xFF006A6A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF6FF7F6),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E7),
    onSecondaryContainer = Color(0xFF051F1F),
    tertiary = Color(0xFF4B607C),
    tertiaryContainer = Color(0xFFD3E4FF),
    onTertiaryContainer = Color(0xFF041C35),
    background = Color(0xFFE0FAFF),
    onBackground = Color(0xFF001F24),
    surface = Color(0xFFE0FAFF),
    onSurface = Color(0xFF001F24),
    surfaceVariant = Color(0xFFB9E6EE),
    onSurfaceVariant = Color(0xFF2F4F55),
    outline = Color(0xFF5F8087),
    outlineVariant = Color(0xFF9FCBD3),
    surfaceContainerLowest = Color(0xFFF2FDFF),
    surfaceContainerLow = Color(0xFFD0F6FD),
    surfaceContainer = Color(0xFFBFF1FA),
    surfaceContainerHigh = Color(0xFFAEEDF7),
    surfaceContainerHighest = Color(0xFF9BE8F5),
)

@Suppress("MagicNumber") // 调色板字面量
private fun cyanDark(): ColorScheme = darkColorScheme(
    primary = Color(0xFF4CDADA),
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF00F5F5),
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFFB0CCCB),
    onSecondary = Color(0xFF1B3534),
    secondaryContainer = Color(0xFF324B4B),
    onSecondaryContainer = Color(0xFFCCE8E7),
    tertiary = Color(0xFFB3C8E8),
    tertiaryContainer = Color(0xFF334863),
    onTertiaryContainer = Color(0xFFD3E4FF),
    background = Color(0xFF001F24),
    onBackground = Color(0xFFA6EEFF),
    surface = Color(0xFF001F24),
    onSurface = Color(0xFFA6EEFF),
    surfaceVariant = Color(0xFF1F4A52),
    onSurfaceVariant = Color(0xFF9FCBD3),
    outline = Color(0xFF6A959D),
    outlineVariant = Color(0xFF2F4F55),
    surfaceContainerLowest = Color(0xFF00171B),
    surfaceContainerLow = Color(0xFF00282E),
    surfaceContainer = Color(0xFF003038),
    surfaceContainerHigh = Color(0xFF003B44),
    surfaceContainerHighest = Color(0xFF004751),
)
