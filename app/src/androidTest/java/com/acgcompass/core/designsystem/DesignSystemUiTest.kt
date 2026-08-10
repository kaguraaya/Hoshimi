package com.acgcompass.core.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.acgcompass.core.designsystem.AiCardUiModel.Confidence
import com.acgcompass.core.designsystem.AiCardUiModel.Generator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 *
 * 覆盖兜底与可访问性场景：
 * - 深色模式（[AcgCompassTheme] `darkTheme = true`）。
 * - 大字体（提高 [LocalDensity] 的 `fontScale`）。
 * - 长标题截断兜底（[WorkCard] 渲染不崩溃且标题节点存在）。
 * - 封面缺失占位（[WorkCard] `coverUrl = null` 显示「暂无封面」）。
 * - 评分缺失（[WorkCard] `ratingText = null` 显示「暂无数据」）。
 *
 * 说明：这些是 instrumented 测试，需要设备 / 模拟器才能运行。
 */
@RunWith(AndroidJUnit4::class)
class DesignSystemUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val longTitle =
        "葬送的芙莉莲：勇者一行旅程结束之后，留下来的人将如何面对漫长的时光与回忆，并继续踏上理解人类的旅程"

    /** 长标题 + 缺失评分 + 深色模式：标题节点应存在并显示，渲染不崩溃。 */
    @Test
    fun workCard_longTitle_darkMode_rendersTitleAndNoData() {
        composeTestRule.setContent {
            AcgCompassTheme(darkTheme = true, dynamicColor = false) {
                WorkCard(
                    model = WorkCardUiModel(
                        coverUrl = "https://example.com/cover.jpg",
                        title = longTitle,
                        subtitle = "葬送のフリーレン · 2023",
                        type = "动画",
                        ratingText = null,
                        sourceTags = listOf("Bangumi", "AniList"),
                        backlogStatus = "想看",
                        completionCost = "长期坑",
                        moodRiskTags = listOf("治愈", "致郁预警"),
                    ),
                )
            }
        }

        // 长标题截断后仍保留前缀，断言节点存在（substring 匹配）。
        composeTestRule
            .onNodeWithText("葬送的芙莉莲", substring = true)
            .assertIsDisplayed()

        // 评分缺失时不显示冗余占位文案。
        composeTestRule
            .onAllNodesWithText("暂无数据")
            .assertCountEquals(0)
    }

    /** 封面缺失（浅色模式 `darkTheme = false`）：渲染占位文案「暂无封面」，不留空白、不崩溃。 */
    @Test
    fun workCard_missingCover_lightMode_showsPlaceholder() {
        composeTestRule.setContent {
            AcgCompassTheme(darkTheme = false, dynamicColor = false) {
                WorkCard(
                    model = WorkCardUiModel(
                        coverUrl = null,
                        title = "未知作品",
                        subtitle = "",
                        type = "游戏",
                        ratingText = null,
                        sourceTags = emptyList(),
                    ),
                )
            }
        }

        composeTestRule
            .onNodeWithText("暂无封面")
            .assertIsDisplayed()
        // 评分缺失时不显示冗余占位文案。
        composeTestRule
            .onAllNodesWithText("暂无数据")
            .assertCountEquals(0)
    }

    /** 浅色模式（`darkTheme = false`）+ 长标题 + 有评分：标题与评分应正常渲染，不崩溃。 */
    @Test
    fun workCard_longTitle_lightMode_rendersTitleAndRating() {
        composeTestRule.setContent {
            AcgCompassTheme(darkTheme = false, dynamicColor = false) {
                WorkCard(
                    model = WorkCardUiModel(
                        coverUrl = "https://example.com/cover.jpg",
                        title = longTitle,
                        subtitle = "葬送のフリーレン · 2023",
                        type = "动画",
                        ratingText = "Bgm 8.9 / Ani 89",
                        sourceTags = listOf("Bangumi", "AniList"),
                    ),
                )
            }
        }

        composeTestRule
            .onNodeWithText("葬送的芙莉莲", substring = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Bgm 8.9 / Ani 89")
            .assertIsDisplayed()
    }

    /** 大字体（提高 fontScale）：组件应仍能正常渲染长标题。 */
    @Test
    fun workCard_largeFontScale_stillRenders() {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            val largeFontDensity = Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * 1.8f,
            )
            CompositionLocalProvider(LocalDensity provides largeFontDensity) {
                AcgCompassTheme(dynamicColor = false) {
                    WorkCard(
                        model = WorkCardUiModel(
                            coverUrl = null,
                            title = longTitle,
                            subtitle = "葬送のフリーレン · 2023",
                            type = "动画",
                            ratingText = "Bgm 8.9 / Ani 89",
                        ),
                    )
                }
            }
        }

        composeTestRule
            .onNodeWithText("葬送的芙莉莲", substring = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText("暂无封面")
            .assertIsDisplayed()
    }

    /** AiCard 深色模式 + 大字体：徽标与重新生成按钮应渲染。 */
    @Test
    fun aiCard_darkMode_largeFont_rendersBadges() {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            val largeFontDensity = Density(
                density = baseDensity.density,
                fontScale = baseDensity.fontScale * 1.8f,
            )
            CompositionLocalProvider(LocalDensity provides largeFontDensity) {
                AcgCompassTheme(darkTheme = true, dynamicColor = false) {
                    AiCard(
                        model = AiCardUiModel(
                            generator = Generator.AI,
                            generatedAtText = "2024-09-30 21:15",
                            sources = listOf("短评", "Reviews", "标签"),
                            confidence = Confidence.HIGH,
                        ),
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("AI 生成").assertIsDisplayed()
        composeTestRule.onNodeWithText("置信度：高").assertIsDisplayed()
        composeTestRule.onNodeWithText("重新生成").assertIsDisplayed()
    }
}
