package com.acgcompass.core.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 顶栏内容区高度（不含状态栏），与 FlClash 页面顶栏一致。 */
val AcgTopBarHeight = 72.dp

/**
 * 顶层页面统一标题栏。
 *
 * 五个顶层页（首页 / 发现 / 待补池 / 时光机 / 我的）共用：标题靠左上、字号更大（headlineSmall）、加粗、统一。
 *
 * inset 归属：外层 Scaffold 不消费顶部 inset（见 [com.acgcompass.app.navigation.AcgApp]），由本 TopAppBar
 * 用**默认 windowInsets** 消费状态栏，使标题栏位于状态栏之下、内容位于标题栏之下，避免遮挡。
 *
 * @param title 页面标题。
 * @param actions 右侧动作槽（如「多选」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcgTopLevelTopBar(
    title: String,
    modifier: Modifier = Modifier,
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(),
    actions: @Composable (androidx.compose.foundation.layout.RowScope.() -> Unit) = {},
    /** false 时不画遮罩：顶栏和下面的分区药丸放在同一个 Column 里、由 Column 统一加遮罩时用。 */
    scrim: Boolean = true,
) {
    TopAppBar(
        expandedHeight = AcgTopBarHeight,
        modifier = if (scrim) modifier.topBarScrim() else modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        },
        colors = colors.copy(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
        actions = actions,
        // 顶栏自己包住状态栏区域。
        windowInsets = TopAppBarDefaults.windowInsets,
    )
}
