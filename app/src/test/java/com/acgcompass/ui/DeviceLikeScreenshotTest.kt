package com.acgcompass.ui

import android.app.Application
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.ImageLoader
import coil.decode.DataSource
import coil.intercept.Interceptor
import coil.request.SuccessResult
import com.acgcompass.app.navigation.AcgBottomNavigation
import com.acgcompass.app.navigation.TopLevelDestination
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.WorkCardUiModel
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.LocalFloatingBarInset
import com.acgcompass.core.ui.UiState
import com.acgcompass.core.ui.topBarBlurSource
import com.acgcompass.domain.cache.CachePolicy
import com.acgcompass.domain.model.ImportItem
import com.acgcompass.domain.model.ImportItemStatus
import com.acgcompass.domain.schedule.BroadcastScheduleEntry
import com.acgcompass.feature.cache.CacheManagementUiState
import com.acgcompass.feature.cache.PolicyCard
import com.acgcompass.feature.discover.DiscoverFilter
import com.acgcompass.feature.discover.DiscoverScreen
import com.acgcompass.feature.discover.DiscoverTab
import com.acgcompass.feature.discover.FilterFacets
import com.acgcompass.feature.discover.RankedWork
import com.acgcompass.feature.discover.RankingScope
import com.acgcompass.feature.home.BacklogSummary
import com.acgcompass.feature.home.ContinueItem
import com.acgcompass.feature.home.DailyDraw
import com.acgcompass.feature.home.HomeScreen
import com.acgcompass.feature.home.HomeUiState
import com.acgcompass.feature.home.HomeWorkCard
import com.acgcompass.feature.home.SyncReminder
import com.acgcompass.feature.home.SyncReminderLine
import com.acgcompass.feature.imports.ImportScreen
import com.acgcompass.feature.imports.ImportUiState
import com.acgcompass.feature.mine.MineScreen
import com.acgcompass.feature.mine.MineStats
import com.acgcompass.feature.mine.MineUiState
import com.acgcompass.feature.recommender.RecommenderInput
import com.acgcompass.feature.recommender.RecommenderScreen
import com.github.takahirom.roborazzi.captureRoboImage
import dev.chrisbanes.haze.HazeState
import java.time.DayOfWeek
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

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
        Coil.setImageLoader(fakeCoverLoader())
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
        scrollHomeTo("同步提醒")
        shot("device-home-dark-2")
    }

    @Test
    fun homeLight() {
        render(dark = false) { SampleHome() }
        shot("device-home-light")
        scrollHomeTo("短篇可补")
        shot("device-home-light-2")
        scrollHomeTo("同步提醒")
        shot("device-home-light-3")
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
    fun mineLight() {
        render(dark = false) { SampleMineWithDock() }
        shot("device-mine-light")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("关于"))
        settle()
        shot("device-mine-light-scrolled")
    }

    @Test
    fun mineDark() {
        render(dark = true) { SampleMineWithDock() }
        shot("device-mine-dark")
    }

    @Test
    fun discoverSearchLight() {
        render(dark = false) { SampleDiscoverSearch() }
        shot("device-discover-search-light")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("比宇宙更远的地方"))
        settle()
        shot("device-discover-search-scrolled")
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

    private fun scrollHomeTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
        settle()
    }

    /** 等封面请求回来并让淡入动画走完，否则截到的是空白占位。 */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            compose.waitForIdle()
            ShadowLooper.idleMainLooper()
            compose.mainClock.advanceTimeBy(COVER_FADE_MS)
        }
        compose.waitForIdle()
    }

    /** 不联网：任何封面地址都回一张按地址取色的渐变图，卡片里才看得到「有封面」时的真实排版。 */
    private fun fakeCoverLoader(): ImageLoader {
        val context = RuntimeEnvironment.getApplication()
        return ImageLoader.Builder(context)
            .dispatcher(Dispatchers.Unconfined)
            .components {
                add(
                    Interceptor { chain ->
                        val seed = chain.request.data.hashCode()
                        val hue = (seed and HUE_MASK) % FULL_HUE
                        val top = AndroidColor.HSVToColor(floatArrayOf(hue.toFloat(), 0.45f, 0.85f))
                        val bottom = AndroidColor.HSVToColor(
                            floatArrayOf((hue + HUE_SHIFT) % FULL_HUE.toFloat(), 0.6f, 0.5f),
                        )
                        SuccessResult(
                            drawable = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(top, bottom)),
                            // 关掉淡入：测试里没有真实时钟推进，淡入会停在全透明。
                            request = chain.request.newBuilder().crossfade(false).build(),
                            dataSource = DataSource.MEMORY,
                        )
                    },
                )
            }
            .build()
    }

    private companion object {
        const val WIDE_FONT_SCALE = 1.15f
        const val COVER_FADE_MS = 600L
        const val SETTLE_ROUNDS = 3
        const val HUE_MASK = 0x7fffffff
        const val FULL_HUE = 360
        const val HUE_SHIFT = 40
    }
}

private fun card(id: String, title: String, subtitle: String, rating: String?, tags: List<String>) = HomeWorkCard(
    workId = id,
    card = WorkCardUiModel(
        coverUrl = "https://example.invalid/cover/$id.jpg",
        title = title,
        subtitle = subtitle,
        type = "TV",
        ratingText = rating,
        sourceTags = listOf("Bangumi"),
        backlogStatus = "想看",
        completionCost = "12 集 · 约 5 小时",
        moodRiskTags = tags,
    ),
)

private fun broadcast(id: String, title: String, day: DayOfWeek, episode: Int) = BroadcastScheduleEntry(
    scheduleId = "s$id",
    workId = id,
    sourceItemId = id,
    title = title,
    titleCn = title,
    coverUrl = "https://example.invalid/cover/$id.jpg",
    dayOfWeek = day,
    episode = episode,
)

@Composable
private fun SampleHome() {
    val frieren = card("1", "葬送的芙莉莲", "葬送のフリーレン · 2023", "Bangumi 8.9", listOf("治愈", "奇幻"))
    val bocchi = card("2", "孤独摇滚！", "ぼっち・ざ・ろっく！ · 2022", "Bangumi 8.6", listOf("音乐", "搞笑"))
    val yorimoi = card("3", "比宇宙更远的地方", "宇宙よりも遠い場所 · 2018", "Bangumi 8.7", listOf("青春"))
    HomeScreen(
        state = UiState.Success(
            HomeUiState(
                seasonalNew = listOf(frieren, bocchi),
                upcomingBroadcasts = listOf(
                    broadcast("1", "葬送的芙莉莲 第二季", DayOfWeek.FRIDAY, 7),
                    broadcast("2", "孤独摇滚！ 第二季", DayOfWeek.SATURDAY, 3),
                    broadcast("3", "比宇宙更远的地方", DayOfWeek.SUNDAY, 12),
                ),
                continueItems = listOf(
                    ContinueItem("3", yorimoi.card, progressText = "看到第 5 集", nextUpText = "下一集 第 6 集"),
                ),
                backlogSummary = BacklogSummary(
                    totalCount = 42,
                    recentlyAdded = bocchi,
                    shortPickable = listOf(yorimoi),
                ),
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

/** 「我的」页 + 悬浮底栏：看成组的行、统计块和底栏在一起的效果。 */
@Composable
private fun SampleMineWithDock() {
    // 和应用里一样：底栏叠在内容上面（毛玻璃取样下面的内容），内容用 LocalFloatingBarInset 让出底部。
    val dockHaze = remember { HazeState() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalFloatingBarInset provides DOCK_SPACE) {
            MineScreen(
                modifier = Modifier.topBarBlurSource(dockHaze),
                state = UiState.Success(
                    MineUiState(
                        stats = MineStats(
                            watched = 312,
                            watching = 6,
                            wantToWatch = 42,
                            onHold = 9,
                            dropped = 17,
                            averageRating = 7.4f,
                            highestRating = 10f,
                        ),
                    ),
                ),
                onNavigateToSettings = {},
                onOpenDataSettings = {},
                onOpenTasteProfile = {},
                onOpenAbout = {},
            )
        }
        AcgBottomNavigation(
            currentDestination = TopLevelDestination.ENTRIES.last(),
            onSelect = {},
            hazeState = dockHaze,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private val DOCK_SPACE = 80.dp

/** 发现-搜索：查询为空时显示历史搜索与最近浏览，搜索框在列表第一项。 */
@Composable
private fun SampleDiscoverSearch() {
    val recent = listOf(
        card("1", "葬送的芙莉莲", "葬送のフリーレン · 2023", "Bangumi 8.9", listOf("治愈", "奇幻")),
        card("2", "孤独摇滚！", "ぼっち・ざ・ろっく！ · 2022", "Bangumi 8.6", listOf("音乐", "搞笑")),
        card("3", "比宇宙更远的地方", "宇宙よりも遠い場所 · 2018", "Bangumi 8.7", listOf("青春")),
        card("4", "学生会也有洞", "生徒会にも穴はある！ · 2025", "Bangumi 7.4", listOf("搞笑", "校园")),
    ).map { RankedWork(workId = it.workId, card = it.card) }
    DiscoverScreen(
        state = UiState.Empty(Cta("输入作品名开始搜索", "search")),
        query = "",
        searchHistory = listOf("芙莉莲", "孤独摇滚", "学生会也有洞", "乱世千金倪亚"),
        recentlyViewed = recent,
        tab = DiscoverTab.SEARCH,
        rankingBoards = emptyList(),
        scoreDiffItems = emptyList(),
        filter = DiscoverFilter(),
        filterFacets = FilterFacets(),
        filteredCards = emptyList(),
        poolLoading = false,
        poolError = null,
        rankingScope = RankingScope.OVERALL,
        scopedRanking = UiState.Empty(Cta("暂无", "none")),
        rankingLoadingMore = false,
        rankingCanLoadMore = false,
        onSelectRankingScope = {},
        onRetryRanking = {},
        onLoadMoreRanking = {},
        onLoadPool = {},
        onQueryChange = {},
        onSearchSubmit = {},
        onSelectHistory = {},
        onRemoveHistory = {},
        onClearHistory = {},
        onRetry = {},
        onOpenWork = {},
        onOverrideMatch = { _, _ -> },
        onTabSelect = {},
        onFilterChange = {},
        onClearFilter = {},
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

/**
 * 近似用户设备上壁纸取色得到的青色配色（浅色页面底偏青、默认卡片色很重；深色主容器是亮青），
 * 交给应用主题去做表面中性化——截图看到的就是应用在这台设备上的实际结果。
 */
@Composable
private fun DeviceLikeTheme(dark: Boolean, content: @Composable () -> Unit) {
    AcgCompassTheme(
        darkTheme = dark,
        dynamicColor = false,
        baseColorScheme = if (dark) cyanDark() else cyanLight(),
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
