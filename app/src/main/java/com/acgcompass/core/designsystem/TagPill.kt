@file:Suppress("MatchingDeclarationName")

package com.acgcompass.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Visual emphasis of a [TagPill]. All tones resolve to Material 3 color roles (dynamic-color safe). */
enum class TagPillTone {
    /** Quiet metadata (data source, media type). */
    Neutral,

    /** Content tags (genre / mood). */
    Secondary,

    /** Highlights that should stand out (match score, special badges). */
    Primary,

    /** Exploratory / unusual items. */
    Tertiary,
}

/**
 * Compact, **non-interactive** tonal label.
 *
 * Replaces the `AssistChip(onClick = {})` / `SuggestionChip(onClick = {})` pattern that was used for
 * purely informational tags: those rendered at the 32dp chip height, showed a ripple and were announced
 * as buttons by TalkBack although tapping them did nothing. A pill is a plain tonal [Surface] with label
 * typography, so dense rows stay readable and the tap target belongs to the card around it.
 */
@Composable
fun TagPill(text: String, modifier: Modifier = Modifier, tone: TagPillTone = TagPillTone.Secondary) {
    val (container, content) = when (tone) {
        // Neutral pills sit on tonal card containers, so a filled tonal background would vanish into the
        // card; they use the plain surface color plus a hairline outline instead.
        TagPillTone.Neutral -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurfaceVariant
        TagPillTone.Secondary ->
            MaterialTheme.colorScheme.secondaryContainer to
                MaterialTheme.colorScheme.onSecondaryContainer
        TagPillTone.Primary ->
            MaterialTheme.colorScheme.primaryContainer to
                MaterialTheme.colorScheme.onPrimaryContainer
        TagPillTone.Tertiary ->
            MaterialTheme.colorScheme.tertiaryContainer to
                MaterialTheme.colorScheme.onTertiaryContainer
    }
    TagPill(
        text = text,
        containerColor = container,
        contentColor = content,
        modifier = modifier,
        border = if (tone == TagPillTone.Neutral) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        } else {
            null
        },
    )
}

/** [TagPill] with explicit colors (e.g. on top of imagery). */
@Composable
fun TagPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
        border = border,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** Wrapping row of [TagPill]s with the standard 6dp gaps. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagPillRow(tags: List<String>, modifier: Modifier = Modifier, tone: TagPillTone = TagPillTone.Secondary) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.forEach { TagPill(text = it, tone = tone) }
    }
}
