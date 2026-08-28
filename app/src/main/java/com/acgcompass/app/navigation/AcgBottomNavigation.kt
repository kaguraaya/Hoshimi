package com.acgcompass.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.SurfaceLevel
import com.acgcompass.core.designsystem.SurfaceScope
import com.acgcompass.core.designsystem.Surfaces

/**
 * 固定五栏底部导航。
 *
 * 渲染 [TopLevelDestination.ENTRIES] 的五个栏目，高亮 [currentDestination] 对应的当前页，
 * 点击时通过 [onSelect] 上抛目标栏目由导航层执行切换（返回栈逻辑见 [AppNavHost]）。
 *
 * 无状态：当前选中项与点击行为均由调用方提供，便于预览与 UI 测试。
 *
 * @param currentDestination 当前所在的顶层目的地；为 `null` 时（位于嵌套路由）不高亮任何栏目。
 * @param onSelect 用户点击某栏目的回调。
 */
@Composable
fun AcgBottomNavigation(
    currentDestination: TopLevelDestination?,
    onSelect: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = isSystemInDarkThemeBySurface()
    // 悬浮的药丸形底栏（对应 FlClash 的 dock）：浮在内容上的东西浅色取卡片级、深色取色块级，并带一层投影；
    // 选中项是比底栏深一级的中性色块，图标和文字换成主色——不用带颜色的指示器。
    val dockLevel = if (dark) SurfaceLevel.Block else SurfaceLevel.Card
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = DOCK_MARGIN, vertical = 8.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(DOCK_HEIGHT),
            shape = CircleShape,
            color = Surfaces.colorOf(dockLevel),
            shadowElevation = 8.dp,
        ) {
            SurfaceScope(dockLevel) {
                Row(
                    modifier = Modifier.padding(6.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TopLevelDestination.ENTRIES.forEach { tab ->
                        DockItem(
                            tab = tab,
                            selected = tab == currentDestination,
                            onClick = { onSelect(tab) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DockItem(tab: TopLevelDestination, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val content = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier
            .fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = if (selected) "${tab.label}（当前页）" else tab.label },
        shape = CircleShape,
        color = if (selected) Surfaces.raisedColor else Color.Transparent,
        contentColor = content,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(
                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = tab.label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun isSystemInDarkThemeBySurface(): Boolean = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE

private val DOCK_HEIGHT = 64.dp
private val DOCK_MARGIN = 20.dp
private const val DARK_LUMINANCE = 0.5f
