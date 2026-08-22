@file:Suppress("MatchingDeclarationName") // 一组扁平化基础组件，文件名取其中最常用的一个。

package com.acgcompass.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * Single-choice pill tabs: equal-width borderless pills, the selected one filled with the primary color.
 * Used for small mutually exclusive sets (2–4 options) in place of an outlined segmented button.
 */
@Composable
fun <T> PillTabs(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(option) }),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.matchParentSize(),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else FlatDefaults.tileColor,
                    content = {},
                )
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
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** Multi-choice chip without an outline: a tonal pill that fills with the primary container when selected. */
@Composable
fun TonalFilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        label = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
        shape = MaterialTheme.shapes.small,
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = FlatDefaults.tileColor,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}
