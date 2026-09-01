package com.acgcompass.core.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 页面内容区统一内边距；各页在此基础上叠加 [PaddingValues]。 */
/**
 * 悬浮底栏占掉的高度。底栏是**叠在内容上面**的（不占布局空间，圆角外面能看到下面的内容），
 * 所以可滚动内容要用这个值把底部内边距加大，最后一项才能滚到底栏上方。非顶层页面为 0。
 */
val LocalFloatingBarInset = compositionLocalOf { 0.dp }

object ScreenContentPadding {
    /** 横向留白。 */
    val Horizontal: Dp = 16.dp

    /**
     * 内容与标题栏之间的顶部留白（用于「直接位于顶部标题栏下方」的列表：首页 / 我的 / 设置 / 我的库 / 口味画像）。
     * 父级已用 `.padding(innerPadding)` 让出标题栏高度，本值是标题栏下方的额外呼吸间距。
     * 默认值保持各页面顶部内容与系统栏的统一间距。
     */
    val Top: Dp = 68.dp

    /**
     * 位于「中间栏（TabRow / 同步动作栏）下方」的列表（发现各 Tab、时光机）使用的顶部留白。
     * 这些列表上方已有中间栏提供分隔，再叠加大段 [Top] 会整体过度下沉，故仅留小段呼吸间距。
     */
    val UnderBarTop: Dp = 10.dp

    /**
     * 底部留白：外层 Scaffold 已消费底部导航栏 / 手势条 inset，此处仅需小段呼吸间距，
     * 避免末项贴边；过大的固定值会导致滚动到底时出现明显空白。
     */
    val Bottom: Dp
        @Composable @ReadOnlyComposable
        get() = BASE_BOTTOM + LocalFloatingBarInset.current

    private val BASE_BOTTOM: Dp = 28.dp

    /**
     * 把 [AcgScreenScaffold] 下发的 [innerPadding]（已含标题栏高度）与统一留白合并，
     * 供 LazyColumn `contentPadding` 使用。
     */
    @Composable
    @ReadOnlyComposable
    fun lazyList(innerPadding: PaddingValues, extraBottom: Dp = 0.dp): PaddingValues = PaddingValues(
        start = Horizontal,
        end = Horizontal,
        top = innerPadding.calculateTopPadding() + Top,
        bottom = Bottom + extraBottom,
    )
}

/**
 * 全应用统一页面脚手架。
 *
 * inset 单一来源模型：外层 [com.acgcompass.app.navigation.AcgApp] 的 Scaffold 已消费状态栏 / 导航栏，
 * 并以 `consumeWindowInsets` 标记。本脚手架的 [Scaffold] 与 [TopAppBar] 一律使用**零 windowInsets**，
 * 不再各自消费状态栏，避免「首张卡片贴顶 / 被裁切」的反复问题。
 *
 * 内容通过 [content] 接收 `innerPadding`（仅标题栏高度），各页应用 [ScreenContentPadding.lazyList]
 * 把它叠加到 LazyColumn 的 `contentPadding`，确保内容从标题栏下方开始、底部不被遮挡。
 *
 * @param title 页面标题。
 * @param largeTitle 是否使用顶层页大标题（headlineSmall 加粗）。
 * @param navigationIcon 左上角导航图标槽（如返回）。
 * @param actions 右上角动作槽。
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
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
                colors = colors,
                // 零 inset，状态栏由外层 Scaffold 统一消费。
                windowInsets = WindowInsets(0, 0, 0, 0),
            )
        },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
    ) { innerPadding ->
        content(innerPadding)
    }
}
