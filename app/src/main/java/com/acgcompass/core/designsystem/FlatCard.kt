@file:Suppress("TooManyFunctions") // 一组互相配套的扁平组件，放在一起便于对照同一套尺寸

package com.acgcompass.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardElevation
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 圆角档位（照 FlClash 的 `AppCorner` 与仪表盘卡片）：同一类东西只用同一档，页面才显得严谨。
 *
 * 徽章 8 · 输入框 / 分段选项 16 · 卡片里的色块 20 · 列表卡片与成组的行 24 · 带标题的分区卡片 32 · 按钮与选项药丸全圆。
 */
object PrismCorner {
    val Badge = RoundedCornerShape(8.dp)

    /** 行里的小缩略图（约 44dp）。 */
    val Thumb = RoundedCornerShape(8.dp)

    /** 卡片里的封面：与圆角 24、内边距 12 的卡片同心。 */
    val Cover = RoundedCornerShape(12.dp)

    /** 直接放在页面上的海报（网格、详情页头图）。 */
    val Poster = RoundedCornerShape(16.dp)
    val Input = RoundedCornerShape(16.dp)
    val Segment = RoundedCornerShape(16.dp)
    val Block = RoundedCornerShape(20.dp)
    val Card = RoundedCornerShape(24.dp)
    val SectionCard = RoundedCornerShape(32.dp)
}

/** 尺寸档位：页面左右留白 16、卡片之间 8、行高至少 56、选项药丸高 36、分段选项高 44。 */
object PrismMetrics {
    val PageGutter = 16.dp
    val CardGap = 8.dp
    val RowMinHeight = 56.dp
    val ChipHeight = 36.dp
    val SegmentHeight = 44.dp
    val HeaderGlyph = 18.dp
    val Hairline = 0.6.dp
}

/** 从当前表面层级取色（见 [Surfaces]）。 */
object FlatDefaults {
    /** 放在当前表面上的卡片 / 色块的填充色。 */
    val cardColor: Color
        @Composable get() = Surfaces.raisedColor

    /** 同 [cardColor]：在卡片里调用时得到的就是「卡片里的色块」那一级。 */
    val tileColor: Color
        @Composable get() = Surfaces.raisedColor

    val CardPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)
}

/**
 * 卡片：没有描边、没有投影，靠比所在表面深一级的填充色立起来；里面的内容自动再深一级。
 * 参数与 Material 的 `Card` 对应，可原地替换（`elevation` / `border` 被忽略——结构不靠线和阴影表达）。
 */
@Suppress("LongParameterList", "UNUSED_PARAMETER") // 与 Material Card 的参数一一对应，便于原地替换
@Composable
fun PrismCard(
    modifier: Modifier = Modifier,
    shape: Shape = PrismCorner.Card,
    colors: CardColors? = null,
    elevation: CardElevation? = null,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val level = Surfaces.raised
    Surface(
        modifier = modifier,
        shape = shape,
        color = colors?.containerColor ?: Surfaces.colorOf(level),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        SurfaceScope(level) { Column(content = content) }
    }
}

/** 可点击的 [PrismCard]：按下只有涟漪，不缩放、不变色块。 */
@Suppress("LongParameterList", "UNUSED_PARAMETER") // 与 Material Card 的参数一一对应，便于原地替换
@Composable
fun PrismCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = PrismCorner.Card,
    colors: CardColors? = null,
    elevation: CardElevation? = null,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val level = Surfaces.raised
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = shape,
        color = colors?.containerColor ?: Surfaces.colorOf(level),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        SurfaceScope(level) { Column(content = content) }
    }
}

/**
 * 带标题的分区卡片（对应 FlClash 的仪表盘卡片）：圆角 32、内边距 20×18，
 * 标题行是一个 18dp 的小图标加标题，右侧可放一个控件。
 */
@Suppress("LongParameterList") // Composable 的可选外观参数。
@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    supporting: String? = null,
    containerColor: Color = Color.Unspecified,
    contentPadding: PaddingValues = FlatDefaults.CardPadding,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val level = Surfaces.raised
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = PrismCorner.SectionCard,
        color = if (containerColor == Color.Unspecified) Surfaces.colorOf(level) else containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        SurfaceScope(level) {
            Column(
                modifier = Modifier.padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (title != null) FlatCardHeader(title, icon, supporting, trailing)
                content()
            }
        }
    }
}

@Composable
private fun FlatCardHeader(
    title: String,
    icon: ImageVector?,
    supporting: String?,
    trailing: @Composable (RowScope.() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(PrismMetrics.HeaderGlyph),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trailing != null) trailing()
        }
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 分组标题：放在卡片**外面**、成组内容的上方，左缘与卡片外缘对齐，字色用次要色。
 * 页面上的分区因此是「标题 + 一张卡片」，而不是卡片套着标题。
 *
 * 给了 [onClick] 时整行可点（如「时间表」点标题进入完整页面）：按压高光是圆角块，左右各多出 8dp，文字位置不变。
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val clickable = if (onClick == null) {
        Modifier
    } else {
        Modifier
            .layout { measurable, constraints ->
                val bleed = HEADER_BLEED.roundToPx()
                val width = constraints.maxWidth + bleed * 2
                val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
                layout(constraints.maxWidth, placeable.height) { placeable.place(-bleed, 0) }
            }
            .clip(PrismCorner.Segment)
            .clickable(onClick = onClick)
            .padding(horizontal = HEADER_BLEED)
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 16.dp).then(clickable),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (trailing != null) trailing()
    }
}

/**
 * 分段选项（互斥，一组只选一个）：圆角 16、高 44。选中的一段是实心主色，其余取深一级的中性色。
 *
 * [scrollable] 为 false 时各段等宽铺满；为 true 时按内容定宽并可横向滑动（选项多或文字长时不截断）。
 */
@Suppress("LongParameterList") // 与 Material 对应组件的参数一一对应，便于原地替换
@Composable
fun <T> PillTabs(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    scrollable: Boolean = options.size > 2,
) {
    val rowModifier = if (scrollable) {
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    } else {
        modifier.fillMaxWidth()
    }
    val idle = Surfaces.raisedColor
    Row(modifier = rowModifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            val container by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else idle,
                animationSpec = tween(SELECT_DURATION_MS),
                label = "segmentContainer",
            )
            val content = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Surface(
                modifier = (if (scrollable) Modifier else Modifier.weight(1f))
                    .height(PrismMetrics.SegmentHeight)
                    // 按压涟漪跟着圆角走：先裁成药丸形状再响应点击。
                    .clip(PrismCorner.Segment)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) }),
                shape = PrismCorner.Segment,
                color = container,
                contentColor = content,
            ) {
                Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
fun TonalFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FlatFilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = modifier)
}

/**
 * 选项药丸（可多选的筛选 / 标签）：全圆、高 36、左右 16，没有描边。
 * 未选中取深一级的中性色，选中是实心主色——颜色只用来表示「选中」。
 */
@Suppress("LongParameterList") // 与 Material 对应组件的参数一一对应，便于原地替换
@Composable
fun FlatFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else Surfaces.raisedColor,
        animationSpec = tween(SELECT_DURATION_MS),
        label = "chipContainer",
    )
    val content = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier
            .height(PrismMetrics.ChipHeight)
            .clip(CircleShape)
            .selectable(selected = selected, enabled = enabled, role = Role.Checkbox, onClick = onClick),
        shape = CircleShape,
        color = container,
        contentColor = if (enabled) content else content.copy(alpha = DISABLED_ALPHA),
    ) {
        ChipContent(label, leadingIcon, trailingIcon)
    }
}

/** 动作药丸（点一下执行一个动作）：外观同未选中的 [FlatFilterChip]。 */
@Suppress("LongParameterList") // 与 Material 对应组件的参数一一对应，便于原地替换
@Composable
fun FlatAssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
) {
    val content = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        modifier = modifier.height(PrismMetrics.ChipHeight),
        enabled = enabled,
        shape = CircleShape,
        color = Surfaces.raisedColor,
        contentColor = if (enabled) content else content.copy(alpha = DISABLED_ALPHA),
    ) {
        ChipContent(label, leadingIcon, trailingIcon)
    }
}

@Composable
private fun ChipContent(
    label: @Composable () -> Unit,
    leadingIcon: @Composable (() -> Unit)?,
    trailingIcon: @Composable (() -> Unit)?,
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (leadingIcon != null) Box(Modifier.size(CHIP_ICON)) { leadingIcon() }
        CompositionLocalProvider(
            androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.labelLarge,
            LocalContentColor provides LocalContentColor.current,
            content = label,
        )
        if (trailingIcon != null) Box(Modifier.size(CHIP_ICON)) { trailingIcon() }
    }
}

/**
 * 分隔：成组的行画在同一张卡片里时，行与行之间是一条发丝线（[Surfaces.hairline]）；
 * 直接放在页面上时不画线，只留间距——页面上的分区靠卡片和留白区分，不靠横线。
 */
@Composable
fun FlatDivider(modifier: Modifier = Modifier) {
    if (Surfaces.level == SurfaceLevel.Page) {
        Spacer(modifier.height(6.dp))
    } else {
        Box(modifier.fillMaxWidth().height(PrismMetrics.Hairline).background(Surfaces.hairline))
    }
}

/** 开关：关闭时是一条没有描边的中性轨道，开启时是主色。 */
@Composable
fun FlatSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            uncheckedBorderColor = Color.Transparent,
            uncheckedTrackColor = Surfaces.colorOf(SurfaceLevel.Inset),
            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
            disabledUncheckedBorderColor = Color.Transparent,
        ),
    )
}

private val CHIP_ICON: Dp = 18.dp
private const val SELECT_DURATION_MS = 240
private const val DISABLED_ALPHA = 0.38f

private val HEADER_BLEED = 8.dp
