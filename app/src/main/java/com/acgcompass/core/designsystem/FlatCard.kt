@file:Suppress("MatchingDeclarationName") // 一组扁平化基础组件，文件名取其中最常用的一个。

package com.acgcompass.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ChipColors
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Flat design primitives.
 *
 * The visual language is deliberately quiet: surfaces are separated by **tone, not lines**. There are no
 * outlines, dividers or drop shadows — a section is a borderless tonal card with generous radius and
 * padding, things inside it sit on a slightly stronger tone, and selection is shown by a filled pill.
 * Everything resolves to Material 3 color roles, so wallpaper-based dynamic color keeps working.
 */
object FlatDefaults {
    /** Tone of a section card on the page background. */
    val cardColor: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow

    /** Tone of a tile / control nested inside a card. */
    val tileColor: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerHigh

    val CardPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
}

/**
 * Borderless tonal section card with an optional header (small glyph + title, optional trailing slot)
 * and an optional one-line supporting text under it.
 */
@Suppress("LongParameterList") // Composable 的可选外观参数。
@Composable
fun FlatCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    supporting: String? = null,
    containerColor: Color = FlatDefaults.cardColor,
    contentPadding: PaddingValues = FlatDefaults.CardPadding,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = containerColor,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        trailing?.invoke(this)
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
            content()
        }
    }
}

/**
 * Press feedback: the element shrinks slightly while pressed and springs back on release — a quiet
 * physical cue that replaces heavier ripples/outlines on flat surfaces.
 */
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.96f): Modifier = composed {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Single-choice pill tabs: borderless pills, the selected one filled with the primary color.
 *
 * With [scrollable] = false the pills share the width equally (good for 2–3 short options). With
 * [scrollable] = true each pill is as wide as its label and the row scrolls horizontally — labels are
 * never truncated, whatever the font or display size.
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
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    } else {
        modifier.fillMaxWidth()
    }
    Row(modifier = rowModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            val interactionSource = remember { MutableInteractionSource() }
            val container by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primary else FlatDefaults.tileColor,
                label = "pillContainer",
            )
            Box(
                modifier = (if (scrollable) Modifier else Modifier.weight(1f))
                    .heightIn(min = 44.dp)
                    .pressScale(interactionSource)
                    .clip(MaterialTheme.shapes.medium)
                    .background(container)
                    .selectable(
                        selected = isSelected,
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        role = Role.Tab,
                        onClick = { onSelect(option) },
                    )
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** Multi-choice chip without an outline: a tonal pill that fills with the primary color when selected. */
@Composable
fun TonalFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FlatFilterChip(selected = selected, onClick = onClick, label = { Text(label) }, modifier = modifier)
}

/**
 * Drop-in replacement for Material's `FilterChip` in the flat style: no outline, tonal when idle,
 * filled with the primary color when selected.
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
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = MaterialTheme.shapes.small,
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = FlatDefaults.tileColor,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
            selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

/** Drop-in replacement for Material's `AssistChip` in the flat style (tonal, no outline). */
@Suppress("LongParameterList") // 与 Material 对应组件的参数一一对应，便于原地替换
@Composable
fun FlatAssistChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.small,
    colors: ChipColors = AssistChipDefaults.assistChipColors(
        containerColor = FlatDefaults.tileColor,
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
) {
    AssistChip(
        onClick = onClick,
        label = label,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        shape = shape,
        colors = colors,
        border = null,
    )
}

/**
 * Stand-in for Material's `HorizontalDivider` in the flat style: sections are separated by space, not by
 * a line, so this only reserves a little vertical room.
 */
@Composable
fun FlatDivider(modifier: Modifier = Modifier) {
    Spacer(modifier = modifier.height(6.dp))
}
