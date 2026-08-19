package com.acgcompass.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.taste.PersonalDivergenceModel
import com.acgcompass.domain.taste.TagCount
import com.acgcompass.domain.taste.TasteSample
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.feature.detail.RatingPredictionBlock
import com.acgcompass.feature.detail.toUi
import com.acgcompass.feature.explore.ExploreCardUiModel
import com.acgcompass.feature.explore.ExploreQueueScreen
import com.acgcompass.feature.explore.ExploreQueueUiState
import com.acgcompass.feature.recommender.RecommendationKind
import com.acgcompass.feature.recommender.RecommendationUiModel
import com.acgcompass.feature.recommender.RecommenderInput
import com.acgcompass.feature.recommender.RecommenderScreen
import com.acgcompass.feature.recommender.TimeBudget
import com.acgcompass.feature.taste.DivergenceSection
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 无设备界面走查：在 JVM 里用 Robolectric 的原生渲染把页面真实画出来，按用户的操作顺序点一遍，
 * 断言每一步的界面状态，并把关键画面截图到 `build/outputs/roborazzi/`。
 *
 * 覆盖的是「界面层的操作逻辑」——页面是无状态 Composable（状态 + 回调），这里用一个最小的状态持有者
 * 代替 ViewModel。它验证不了真机上的性能、手势手感和网络，但能保证：按钮点得到、状态切得对、
 * 文案不被截断、深浅色都能正常渲染。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
class ScreenWalkthroughTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String) = compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    // 方法名用 ASCII：非 ASCII 方法名会生成非 ASCII 的类文件名，在部分文件系统 / locale 下无法编译。
    /** 今晚看什么：选时间 → 选标签 → 生成 → 看到三档结果 → 进详情 / 返回。 */
    @Test
    fun tonightFlow() {
        var input by mutableStateOf(RecommenderInput())
        var result by mutableStateOf<UiState<List<RecommendationUiModel>>>(
            UiState.Empty(Cta("选择条件后生成推荐", "submit")),
        )
        var opened: String? = null
        var backPressed = false
        compose.setContent {
            AcgCompassTheme(darkTheme = false, dynamicColor = false) {
                RecommenderScreen(
                    input = input,
                    result = result,
                    availableTags = listOf("校园", "恋爱", "日常", "搞笑", "催泪", "悬疑", "科幻"),
                    onSelectTime = { input = input.copy(time = it) },
                    onToggleTag = { tag ->
                        val selected = input.selectedTags
                        input = input.copy(selectedTags = if (tag in selected) selected - tag else selected + tag)
                    },
                    onToggleAcceptance = {},
                    onToggleIndecision = { input = input.copy(indecisionMode = !input.indecisionMode) },
                    onToggleFinalsProtection = {},
                    onToggleLateNight = {},
                    onSelectCandidatePool = { input = input.copy(candidatePool = it) },
                    onSubmit = { result = UiState.Success(sampleRecommendations) },
                    onOpenWork = { opened = it },
                    onBack = { backPressed = true },
                )
            }
        }

        // 1. 初始：没选时间，主按钮禁用并提示原因。
        compose.onNodeWithText("先选择可用时间").assertIsDisplayed().assertIsNotEnabled()
        shot("tonight-1-initial")

        // 2. 选时间与标签。
        compose.onNodeWithText(TimeBudget.TWO_THREE_HOURS.label()).performClick()
        compose.onNodeWithText("校园").performClick()
        compose.onNodeWithText("恋爱").performClick()
        assertEquals(setOf("校园", "恋爱"), input.selectedTags)
        compose.onNodeWithText("已选 2 个，命中越多越优先").assertIsDisplayed()
        shot("tonight-2-selected")

        // 3. 切到「全部作品」候选池、打开单一推荐模式（整行可点）。
        compose.onNodeWithText("全部作品").performClick()
        compose.onNodeWithText("单一推荐模式").performScrollTo().performClick()
        assertTrue(input.indecisionMode)

        // 4. 生成：按钮可用 → 点击 → 结果出现，文案变为「换一批」。
        compose.onNodeWithText("生成今晚推荐").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("换一批").assertExists()
        compose.onNodeWithText("稳妥选择").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("匹配 86%").assertExists()
        shot("tonight-3-results")

        // 5. 点作品卡片进入详情；返回按钮可用。
        compose.onNodeWithText("樱花庄的宠物女孩").performScrollTo().performClick()
        assertEquals("w1", opened)
        compose.onNodeWithContentDescription("返回").performClick()
        assertTrue(backPressed)
    }

    /** 探索队列：翻面看简介 → 按钮加入 → 右滑加入 → 暂不 → 本批完成 → 再来一批。 */
    @Test
    fun exploreFlow() {
        var state by mutableStateOf<ExploreQueueUiState>(ExploreQueueUiState.Ready(sampleExploreCards, 0))
        var liked = 0
        var skipped = 0
        fun advance() {
            val ready = state as ExploreQueueUiState.Ready
            state = if (ready.index + 1 >= ready.cards.size) {
                ExploreQueueUiState.Finished(liked, skipped)
            } else {
                ready.copy(index = ready.index + 1)
            }
        }
        compose.setContent {
            AcgCompassTheme(darkTheme = false, dynamicColor = false) {
                ExploreQueueScreen(
                    state = state,
                    onLike = {
                        liked++
                        advance()
                    },
                    onSkip = {
                        skipped++
                        advance()
                    },
                    onRegenerate = { state = ExploreQueueUiState.Ready(sampleExploreCards, 0) },
                    onOpenWork = {},
                    onEnsureSynopsis = {},
                    onBack = {},
                )
            }
        }

        // 第 1 张：跨界候选，带预计评分。
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
        compose.onNodeWithText("跨界探索").assertIsDisplayed()
        compose.onNodeWithText("预计你会打 8.1 · 大众基线 8.4").assertIsDisplayed()
        shot("explore-1-card")

        // 点卡片翻面看简介，再翻回来。
        compose.onNodeWithText("葬送的芙莉莲").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("查看详情").assertIsDisplayed()
        shot("explore-2-back")
        compose.onNodeWithText("再次点击卡片翻回正面").performClick()
        compose.mainClock.advanceTimeBy(600)

        // 按钮加入：卡片飞出后前进到第 2 张。
        compose.onNodeWithText("加入待补池").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("2 / 3").assertIsDisplayed()
        assertEquals(1, liked)

        // 右滑加入（越过阈值）。
        compose.onNodeWithText("孤独摇滚！").performTouchInput { swipeRight() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("3 / 3").assertIsDisplayed()
        assertEquals(2, liked)

        // 最后一张点「暂不」→ 完成页显示统计。
        compose.onNodeWithText("暂不").performClick()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("本批探索完成").assertIsDisplayed()
        compose.onNodeWithText("加入待补池 2 部").assertIsDisplayed()
        compose.onNodeWithText("暂不 1 部").assertIsDisplayed()
        shot("explore-3-finished")

        // 再来一批回到第 1 张。
        compose.onNodeWithText("再来一批").performClick()
        compose.onNodeWithText("1 / 3").assertIsDisplayed()
    }

    /** 评分预测块 + 分歧画像（浅色）。 */
    @Test
    fun divergenceLight() {
        val model = PersonalDivergenceModel.fit(divergenceSamples())!!
        val prediction = model.predict(feature("new", listOf("悬疑", "科幻", "群像"), 7.6f)).toUi()
        compose.setContent {
            AcgCompassTheme(darkTheme = false, dynamicColor = false) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        RatingPredictionBlock(prediction)
                        DivergenceSection(model)
                    }
                }
            }
        }
        compose.onNodeWithText("预计你会打").assertIsDisplayed()
        compose.onNodeWithText("你和大众的分歧").assertIsDisplayed()
        compose.onNodeWithText("比大众更偏爱").assertIsDisplayed()
        shot("divergence-light")
    }

    /** 评分预测块 + 分歧画像（深色）。 */
    @Test
    fun divergenceDark() {
        val model = PersonalDivergenceModel.fit(divergenceSamples())!!
        val prediction = model.predict(feature("new", listOf("后宫", "校园", "日常"), 7.6f)).toUi()
        compose.setContent {
            AcgCompassTheme(darkTheme = true, dynamicColor = false) {
                Surface {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        RatingPredictionBlock(prediction)
                        DivergenceSection(model)
                    }
                }
            }
        }
        compose.onNodeWithText("不如大众买账").assertIsDisplayed()
        shot("divergence-dark")
    }
}

private val sampleRecommendations = listOf(
    RecommendationUiModel(
        kind = RecommendationKind.SAFE,
        workId = "w1",
        card = WorkCardUiModel(
            coverUrl = null,
            title = "樱花庄的宠物女孩",
            subtitle = "2012 · 2012-10-08",
            type = "动画",
            ratingText = null,
            sourceTags = listOf("BANGUMI"),
            moodRiskTags = listOf("校园", "恋爱", "治愈"),
        ),
        reason = "综合最贴合今晚的条件。是一部典型的校园恋爱日常喜剧，「搞笑」成分较淡，社区均分约 7.6，预计你会打 8.2",
        matchPercent = 86,
    ),
    RecommendationUiModel(
        kind = RecommendationKind.GAMBLE,
        workId = "w2",
        card = WorkCardUiModel(
            coverUrl = null,
            title = "月色真美",
            subtitle = "2017 · 2017-04-06",
            type = "动画",
            ratingText = null,
            sourceTags = listOf("BANGUMI"),
            moodRiskTags = listOf("恋爱", "青春"),
        ),
        reason = "同样高分，但换个题材。「恋爱」是它的核心气质；题材与《樱花庄的宠物女孩》错开，换个口味",
        matchPercent = 71,
    ),
    RecommendationUiModel(
        kind = RecommendationKind.WILDCARD,
        workId = "w3",
        card = WorkCardUiModel(
            coverUrl = null,
            title = "笨蛋，测验，召唤兽",
            subtitle = "2010 · 2010-01-06",
            type = "动画",
            ratingText = null,
            sourceTags = listOf("BANGUMI"),
            moodRiskTags = listOf("搞笑", "校园"),
        ),
        reason = "高分候选里的随机惊喜。「校园」是它的核心气质，缺少「恋爱」",
        matchPercent = 64,
    ),
)

private val sampleExploreCards = listOf(
    ExploreCardUiModel(
        workId = "e1",
        coverUrl = null,
        title = "葬送的芙莉莲",
        meta = "28 话 · 2023 · 动画",
        tastePercent = "58%",
        tasteFraction = 0.58f,
        tasteQualitative = "可能感觉一般",
        reason = "社区评分 8.9，口碑扎实，但题材在你常看的范围之外——换换口味的跨界候选",
        tags = listOf("奇幻", "冒险", "治愈", "漫画改"),
        synopsis = "勇者一行打倒魔王之后，精灵魔法使踏上了重新认识人类的旅途。",
        discovery = true,
        predictionText = "预计你会打 8.1 · 大众基线 8.4",
    ),
    ExploreCardUiModel(
        workId = "e2",
        coverUrl = null,
        title = "孤独摇滚！",
        meta = "12 话 · 2022 · 动画",
        tastePercent = "84%",
        tasteFraction = 0.84f,
        tasteQualitative = "与口味画像高度匹配",
        reason = "命中「题材·音乐 + 题材·日常」，与长期口味画像接近",
        tags = listOf("音乐", "日常", "搞笑"),
        synopsis = null,
        predictionText = "预计你会打 8.6 · 大众基线 8.0",
    ),
    ExploreCardUiModel(
        workId = "e3",
        coverUrl = null,
        title = "某部后宫番",
        meta = "12 话 · 2019 · 动画",
        tastePercent = "41%",
        tasteFraction = 0.41f,
        tasteQualitative = "可以试试看",
        reason = "与口味画像重合较少，作为探索性候选",
        tags = listOf("后宫", "校园"),
        synopsis = null,
    ),
)

private fun feature(id: String, tags: List<String>, community: Float) = WorkFeature(
    subjectId = id,
    tagCounts = tags.mapIndexed { i, t -> TagCount(t, 100 - i * 10) } + TagCount("2018年4月", 30),
    bangumiScore = community,
    bangumiVotes = 4000,
    eps = 12,
    titleVariants = listOf(id),
)

/** 偏爱悬疑 / 群像、不买账后宫的示例用户。 */
private fun divergenceSamples(): List<TasteSample> {
    val pools = listOf(
        listOf("悬疑", "科幻", "群像") to 2,
        listOf("后宫", "校园", "恋爱") to -2,
        listOf("日常", "治愈", "音乐") to 0,
        listOf("战斗", "热血", "冒险") to 0,
    )
    return (0 until 60).map { i ->
        val (tags, bias) = pools[i % pools.size]
        val community = 6.2f + (i % 9) * 0.3f
        TasteSample("s$i", (community + bias).toInt().coerceIn(1, 10), null, i * 1000L, feature("s$i", tags, community))
    }
}
