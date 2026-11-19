package com.acgcompass.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 页面内容区统一内边距；各页在此基础上叠加 [PaddingValues]。 */
/**
 * 悬浮底栏占掉的高度。底栏是**叠在内容上面**的（不占布局空间，圆角外面能看到下面的内容），
 * 所以可滚动内容要用这个值把底部内边距加大，最后一项才能滚到底栏上方。非顶层页面为 0。
 */
val LocalFloatingBarInset = compositionLocalOf { 0.dp }

/**
 * 状态栏高度。顶栏自己包住状态栏，铺在顶栏下面的列表要把它算进顶部留白。
 */
@Composable
private fun statusBarTop(): Dp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

/**
 * 毛玻璃顶栏（含其下固定的标签栏）的总高度。页面把内容铺到顶栏下面时提供它，
 * 用 [ScreenContentPadding.UnderBarTop] 的列表据此自动让出顶部。没铺到下面的页面为 0。
 */
val LocalHeaderInset = compositionLocalOf { 0.dp }

object ScreenContentPadding {
    /** 横向留白。 */
    val Horizontal: Dp = 16.dp

    /** 第一项内容与顶栏底边之间的呼吸间距。 */
    val TopGap: Dp = 12.dp

    /**
     * 铺在标准顶栏（状态栏 + [AcgTopBarHeight]）下面的列表的顶部留白。
     * 页面内容一律**不**加顶部内边距、铺到顶栏下面（顶栏遮罩才看得到下面滚过的内容），由列表用这个值让出顶栏。
     */
    val Top: Dp
        @Composable
        get() = statusBarTop() + AcgTopBarHeight + TopGap

    /** 按 Scaffold 下发的实际顶部高度（顶栏可能带分区药丸）算列表顶部留白。 */
    fun top(innerPadding: PaddingValues, gap: Dp = TopGap): Dp = innerPadding.calculateTopPadding() + gap

    /**
     * 顶栏下面带分区药丸 / 动作栏的页面（发现各分区、时光机），顶部高度由 [LocalHeaderInset] 提供。
     */
    val UnderBarTop: Dp
        @Composable @ReadOnlyComposable
        get() = BASE_UNDER_BAR_TOP + LocalHeaderInset.current

    private val BASE_UNDER_BAR_TOP: Dp = 10.dp

    /**
     * 底部留白：外层 Scaffold 已消费底部导航栏 / 手势条 inset，此处仅需小段呼吸间距，
     * 避免末项贴边；过大的固定值会导致滚动到底时出现明显空白。
     */
    val Bottom: Dp
        @Composable @ReadOnlyComposable
        get() = BASE_BOTTOM + LocalFloatingBarInset.current

    private val BASE_BOTTOM: Dp = 28.dp

    /** [AcgScreenScaffold] 页面的列表内边距：顶部让出顶栏（含 [header]），底部让出底栏。 */
    @Composable
    fun lazyList(innerPadding: PaddingValues, extraBottom: Dp = 0.dp): PaddingValues = PaddingValues(
        start = Horizontal,
        end = Horizontal,
        top = top(innerPadding),
        bottom = Bottom + extraBottom,
    )
}

/**
 * 全应用统一页面脚手架。
 *
 * 顶栏自己包住状态栏，带 [topBarScrim] 遮罩；内容区铺满整个页面（包括顶栏下面），不加顶部内边距。
 * [content] 收到的 `innerPadding.top` 是顶栏（含 [header]）的实际高度，列表用 [ScreenContentPadding.lazyList]
 * 或 [ScreenContentPadding.top] 把它加进 `contentPadding`，第一项从顶栏下方开始，滚动时从遮罩下面经过。
 *
 * @param title 页面标题。
 * @param largeTitle 是否使用顶层页大标题（headlineSmall 加粗）。
 * @param navigationIcon 左上角导航图标槽（如返回）。
 * @param actions 右上角动作槽。
 * @param header 顶栏下方固定的一排内容（如分组药丸），和顶栏共用一层遮罩。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // 通用页面骨架：每个参数都对应 Material Scaffold / TopAppBar 的一个可选部件
@Composable
fun AcgScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    largeTitle: Boolean = true,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    header: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(Modifier.topBarScrim()) {
                TopAppBar(
                    expandedHeight = AcgTopBarHeight,
                    title = {
                        Text(
                            text = title,
                            style = if (largeTitle) {
                                MaterialTheme.typography.headlineSmall
                            } else {
                                MaterialTheme.typography.titleLarge
                            },
                            fontWeight = if (largeTitle) FontWeight.Bold else FontWeight.SemiBold,
                        )
                    },
                    navigationIcon = navigationIcon,
                    actions = actions,
                    // 底色交给遮罩画；调用方给的标题 / 图标颜色保留。
                    colors = colors.copy(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                    // 顶栏自己包住状态栏区域，遮罩因此一直延伸到屏幕顶端。
                    windowInsets = TopAppBarDefaults.windowInsets,
                )
                header?.invoke()
            }
        },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) { content(innerPadding) }
    }
}
