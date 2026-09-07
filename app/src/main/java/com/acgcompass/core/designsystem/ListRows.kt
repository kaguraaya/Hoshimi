package com.acgcompass.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 成组的行（照 FlClash 的 grouped list）：一张卡片里放几行，行与行之间一条发丝线（从文字起点开始画），
 * 行首是一个圆形图标底，行尾是箭头或一个控件。
 */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    PrismCard(modifier = modifier.fillMaxWidth(), content = content)
}

/** 行间分隔：从图标右侧开始，不切断图标列。 */
@Composable
fun RowGroupDivider() {
    FlatDivider(Modifier.padding(start = ROW_TEXT_INSET))
}

/**
 * 圆形图标底：标识性的图标（入口、来源、功能）放在比所在表面深一级的中性圆底上，图标用次要色。
 * 颜色只表示状态，所以这里不用主色。
 */
@Composable
fun IconWell(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = WELL_SIZE) {
    Surface(modifier = modifier.size(size), shape = CircleShape, color = Surfaces.raisedColor) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(size * ICON_RATIO),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 文字图标底：没有合适图标的来源（Bangumi、VNDB…）用首字母。 */
@Composable
fun LetterWell(text: String, modifier: Modifier = Modifier, size: Dp = WELL_SIZE) {
    Surface(modifier = modifier.size(size), shape = CircleShape, color = Surfaces.raisedColor) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text.take(1).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一行：[leading]（通常是 [IconWell]）+ 标题 / 副标题 + [trailing]（默认箭头）。
 * 行高至少 56，左右 16；整行可点。
 */
@Suppress("LongParameterList") // 行的几个可选部件
@Composable
fun NavRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = { Chevron() },
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = PrismMetrics.RowMinHeight)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val WELL_SIZE: Dp = 36.dp
private const val ICON_RATIO = 0.56f

/** 发丝线起点 = 左边距 16 + 图标底 36 + 间距 16。 */
private val ROW_TEXT_INSET: Dp = 68.dp
