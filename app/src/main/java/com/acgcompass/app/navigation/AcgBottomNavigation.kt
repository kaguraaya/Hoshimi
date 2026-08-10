package com.acgcompass.app.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

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
    NavigationBar(modifier = modifier) {
        TopLevelDestination.ENTRIES.forEach { tab ->
            val selected = tab == currentDestination
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(
                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label) },
                modifier = Modifier.semantics {
                    contentDescription = if (selected) "${tab.label}（当前页）" else tab.label
                },
            )
        }
    }
}
