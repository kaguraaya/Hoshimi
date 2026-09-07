package com.acgcompass.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.acgcompass.domain.model.ThemePreset
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual

class ThemePresetContrastTest : StringSpec({
    "all preset foreground pairs meet normal-text contrast" {
        ThemePreset.entries.forEach { preset ->
            listOf(false, true).forEach { dark ->
                val colors = presetColorScheme(preset, dark)
                contrast(colors.onPrimary, colors.primary) shouldBeGreaterThanOrEqual 4.5f
                contrast(colors.onPrimaryContainer, colors.primaryContainer) shouldBeGreaterThanOrEqual 4.5f
                contrast(colors.onSurface, colors.surface) shouldBeGreaterThanOrEqual 4.5f
                contrast(colors.onSurfaceVariant, colors.surfaceVariant) shouldBeGreaterThanOrEqual 4.5f
                // 经过表面层级改写后（应用里实际用的配色）同样要达标。
                val (applied, surfaces) = prismColorScheme(colors)
                contrast(applied.onPrimary, applied.primary) shouldBeGreaterThanOrEqual 4.5f
                contrast(applied.onPrimaryContainer, applied.primaryContainer) shouldBeGreaterThanOrEqual 4.5f
                listOf(surfaces.page, surfaces.card, surfaces.block).forEach { surface ->
                    contrast(applied.onSurface, surface) shouldBeGreaterThanOrEqual 4.5f
                    contrast(applied.onSurfaceVariant, surface) shouldBeGreaterThanOrEqual 4.5f
                }
            }
        }
    }
})

private fun contrast(foreground: Color, background: Color): Float {
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    return (lighter + 0.05f) / (darker + 0.05f)
}
