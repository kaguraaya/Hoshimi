@file:Suppress("MagicNumber") // ARGB literals are the named, contrast-checked theme tokens.

package com.acgcompass.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.acgcompass.domain.model.ThemePreset

/**
 * Brand color palette and Material 3 color schemes for ACG Compass .
 *
 * These hand-tuned light/dark schemes are the brand fallback used on devices that do not support
 * Android 12+ dynamic color. On Android 12+ they are replaced at runtime by the wallpaper-derived
 * dynamic scheme (see [AcgCompassTheme]).
 *
 * The palette is seeded from the brand blue (`#1F5EFF`) and follows the Material 3 tonal role
 * naming so every color slot the design system relies on (primary/secondary/tertiary containers,
 * surface tones, error, outline) has an explicit, accessible value in both light and dark.
 */

// ---- Brand seed tones (kept internal so the rest of the app reads colors via MaterialTheme) ----
internal val AcgBlue = Color(0xFF1F5EFF)
internal val AcgBlueLight = Color(0xFF7FB3FF)
internal val AcgNavy = Color(0xFF1F2A44)
internal val AcgSurfaceLight = Color(0xFFFDFBFF)
internal val AcgSurfaceDark = Color(0xFF12131A)

// ---- Light scheme roles ----
private val md_light_primary = Color(0xFF1F5EFF)
private val md_light_onPrimary = Color(0xFFFFFFFF)
private val md_light_primaryContainer = Color(0xFFDAE2FF)
private val md_light_onPrimaryContainer = Color(0xFF001847)

private val md_light_secondary = Color(0xFF565E71)
private val md_light_onSecondary = Color(0xFFFFFFFF)
private val md_light_secondaryContainer = Color(0xFFDAE2F9)
private val md_light_onSecondaryContainer = Color(0xFF131C2B)

private val md_light_tertiary = Color(0xFF705574)
private val md_light_onTertiary = Color(0xFFFFFFFF)
private val md_light_tertiaryContainer = Color(0xFFFAD8FD)
private val md_light_onTertiaryContainer = Color(0xFF29132E)

private val md_light_error = Color(0xFFBA1A1A)
private val md_light_onError = Color(0xFFFFFFFF)
private val md_light_errorContainer = Color(0xFFFFDAD6)
private val md_light_onErrorContainer = Color(0xFF410002)

private val md_light_background = Color(0xFFFDFBFF)
private val md_light_onBackground = Color(0xFF1B1B1F)
private val md_light_surface = Color(0xFFFDFBFF)
private val md_light_onSurface = Color(0xFF1B1B1F)
private val md_light_surfaceVariant = Color(0xFFE1E2EC)
private val md_light_onSurfaceVariant = Color(0xFF44464F)
private val md_light_outline = Color(0xFF757780)
private val md_light_outlineVariant = Color(0xFFC5C6D0)
private val md_light_inverseSurface = Color(0xFF303034)
private val md_light_inverseOnSurface = Color(0xFFF2F0F4)
private val md_light_inversePrimary = Color(0xFFB1C5FF)
private val md_light_scrim = Color(0xFF000000)

// Tonal surface roles (Material 3 surface containers), tinted toward the brand blue.
private val md_light_surfaceDim = Color(0xFFD9D9E0)
private val md_light_surfaceBright = Color(0xFFFAF8FF)
private val md_light_surfaceContainerLowest = Color(0xFFFFFFFF)
private val md_light_surfaceContainerLow = Color(0xFFF4F3FA)
private val md_light_surfaceContainer = Color(0xFFEEEDF4)
private val md_light_surfaceContainerHigh = Color(0xFFE8E7EF)
private val md_light_surfaceContainerHighest = Color(0xFFE2E2E9)

// ---- Dark scheme roles ----
private val md_dark_primary = Color(0xFFB1C5FF)
private val md_dark_onPrimary = Color(0xFF002C71)
private val md_dark_primaryContainer = Color(0xFF00419E)
private val md_dark_onPrimaryContainer = Color(0xFFDAE2FF)

private val md_dark_secondary = Color(0xFFBEC6DC)
private val md_dark_onSecondary = Color(0xFF283041)
private val md_dark_secondaryContainer = Color(0xFF3E4759)
private val md_dark_onSecondaryContainer = Color(0xFFDAE2F9)

private val md_dark_tertiary = Color(0xFFDDBCE0)
private val md_dark_onTertiary = Color(0xFF3F2844)
private val md_dark_tertiaryContainer = Color(0xFF573E5C)
private val md_dark_onTertiaryContainer = Color(0xFFFAD8FD)

private val md_dark_error = Color(0xFFFFB4AB)
private val md_dark_onError = Color(0xFF690005)
private val md_dark_errorContainer = Color(0xFF93000A)
private val md_dark_onErrorContainer = Color(0xFFFFDAD6)

private val md_dark_background = Color(0xFF12131A)
private val md_dark_onBackground = Color(0xFFE3E2E6)
private val md_dark_surface = Color(0xFF12131A)
private val md_dark_onSurface = Color(0xFFE3E2E6)
private val md_dark_surfaceVariant = Color(0xFF44464F)
private val md_dark_onSurfaceVariant = Color(0xFFC5C6D0)
private val md_dark_outline = Color(0xFF8E9099)
private val md_dark_outlineVariant = Color(0xFF44464F)
private val md_dark_inverseSurface = Color(0xFFE3E2E6)
private val md_dark_inverseOnSurface = Color(0xFF303034)
private val md_dark_inversePrimary = Color(0xFF1F5EFF)
private val md_dark_scrim = Color(0xFF000000)

// Tonal surface roles (Material 3 surface containers), tinted toward the brand blue.
private val md_dark_surfaceDim = Color(0xFF12131A)
private val md_dark_surfaceBright = Color(0xFF383940)
private val md_dark_surfaceContainerLowest = Color(0xFF0D0E14)
private val md_dark_surfaceContainerLow = Color(0xFF1A1B22)
private val md_dark_surfaceContainer = Color(0xFF1E1F26)
private val md_dark_surfaceContainerHigh = Color(0xFF292A31)
private val md_dark_surfaceContainerHighest = Color(0xFF33343C)

/** Brand light color scheme (fallback for devices without dynamic color). */
internal val LightColors = lightColorScheme(
    primary = md_light_primary,
    onPrimary = md_light_onPrimary,
    primaryContainer = md_light_primaryContainer,
    onPrimaryContainer = md_light_onPrimaryContainer,
    secondary = md_light_secondary,
    onSecondary = md_light_onSecondary,
    secondaryContainer = md_light_secondaryContainer,
    onSecondaryContainer = md_light_onSecondaryContainer,
    tertiary = md_light_tertiary,
    onTertiary = md_light_onTertiary,
    tertiaryContainer = md_light_tertiaryContainer,
    onTertiaryContainer = md_light_onTertiaryContainer,
    error = md_light_error,
    onError = md_light_onError,
    errorContainer = md_light_errorContainer,
    onErrorContainer = md_light_onErrorContainer,
    background = md_light_background,
    onBackground = md_light_onBackground,
    surface = md_light_surface,
    onSurface = md_light_onSurface,
    surfaceVariant = md_light_surfaceVariant,
    onSurfaceVariant = md_light_onSurfaceVariant,
    outline = md_light_outline,
    outlineVariant = md_light_outlineVariant,
    inverseSurface = md_light_inverseSurface,
    inverseOnSurface = md_light_inverseOnSurface,
    inversePrimary = md_light_inversePrimary,
    scrim = md_light_scrim,
    // Tonal surface roles. Material 3 cards, navigation bars, sheets and search fields read these;
    // left unset they fall back to the library's baseline (purple-tinted) values, which clash with
    // the blue brand palette whenever dynamic color is off or unavailable (Android < 12).
    surfaceTint = md_light_primary,
    surfaceDim = md_light_surfaceDim,
    surfaceBright = md_light_surfaceBright,
    surfaceContainerLowest = md_light_surfaceContainerLowest,
    surfaceContainerLow = md_light_surfaceContainerLow,
    surfaceContainer = md_light_surfaceContainer,
    surfaceContainerHigh = md_light_surfaceContainerHigh,
    surfaceContainerHighest = md_light_surfaceContainerHighest,
)

/** Brand dark color scheme (fallback for devices without dynamic color). */
internal val DarkColors = darkColorScheme(
    primary = md_dark_primary,
    onPrimary = md_dark_onPrimary,
    primaryContainer = md_dark_primaryContainer,
    onPrimaryContainer = md_dark_onPrimaryContainer,
    secondary = md_dark_secondary,
    onSecondary = md_dark_onSecondary,
    secondaryContainer = md_dark_secondaryContainer,
    onSecondaryContainer = md_dark_onSecondaryContainer,
    tertiary = md_dark_tertiary,
    onTertiary = md_dark_onTertiary,
    tertiaryContainer = md_dark_tertiaryContainer,
    onTertiaryContainer = md_dark_onTertiaryContainer,
    error = md_dark_error,
    onError = md_dark_onError,
    errorContainer = md_dark_errorContainer,
    onErrorContainer = md_dark_onErrorContainer,
    background = md_dark_background,
    onBackground = md_dark_onBackground,
    surface = md_dark_surface,
    onSurface = md_dark_onSurface,
    surfaceVariant = md_dark_surfaceVariant,
    onSurfaceVariant = md_dark_onSurfaceVariant,
    outline = md_dark_outline,
    outlineVariant = md_dark_outlineVariant,
    inverseSurface = md_dark_inverseSurface,
    inverseOnSurface = md_dark_inverseOnSurface,
    inversePrimary = md_dark_inversePrimary,
    scrim = md_dark_scrim,
    surfaceTint = md_dark_primary,
    surfaceDim = md_dark_surfaceDim,
    surfaceBright = md_dark_surfaceBright,
    surfaceContainerLowest = md_dark_surfaceContainerLowest,
    surfaceContainerLow = md_dark_surfaceContainerLow,
    surfaceContainer = md_dark_surfaceContainer,
    surfaceContainerHigh = md_dark_surfaceContainerHigh,
    surfaceContainerHighest = md_dark_surfaceContainerHighest,
)

/**
 * 由预设的种子色生成配色（照 FlClash 的 vivid 风格：主色就是所选颜色，表面近灰）。
 *
 * - 浅色：主色保留种子色的色相，明度夹在 35–48，白字在上面对比度不低于 4.5；
 * - 深色：明度提 3 再夹在 62–80，配深色文字（同色相、明度 10）；
 * - 表面取种子色的色相、极低彩度，交给 [prismColorScheme] 做层级；主色容器同样由它按主色换算。
 */
internal fun presetColorScheme(preset: ThemePreset, dark: Boolean): ColorScheme {
    val base = if (dark) DarkColors else LightColors
    val seed = Color(preset.seed)
    val seedTone = toneOf(seed)
    val onPrimary = if (dark) withTone(seed, ON_DARK_PRIMARY_TONE, SEED_CHROMA) else Color.White
    val startTone = if (dark) {
        (seedTone + DARK_LIFT).coerceIn(DARK_MIN_TONE, DARK_MAX_TONE)
    } else {
        seedTone.coerceIn(LIGHT_MIN_TONE, LIGHT_MAX_TONE)
    }
    // 饱和色超出 sRGB 色域会被截断、实际亮度偏离设定明度：实测对比度，不够就往远离文字的方向逐级调。
    val primary = readableTone(seed, startTone, onPrimary, step = if (dark) 1.0 else -1.0)
    val inverse = if (dark) {
        withTone(seed, seedTone.coerceIn(LIGHT_MIN_TONE, LIGHT_MAX_TONE), SEED_CHROMA)
    } else {
        withTone(seed, (seedTone + DARK_LIFT).coerceIn(DARK_MIN_TONE, DARK_MAX_TONE), SEED_CHROMA)
    }
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        surfaceTint = primary,
        inversePrimary = inverse,
        // 表面带一点种子色的色相（彩度由 prismColorScheme 压到近灰）。
        surface = withTone(seed, toneOf(base.surface), SURFACE_HUE_CHROMA),
        background = withTone(seed, toneOf(base.background), SURFACE_HUE_CHROMA),
    )
}

/** 从 [startTone] 起按 [step] 调明度，直到 [text] 在上面的对比度达到 [MIN_CONTRAST]（最多调 [MAX_TONE_STEPS] 级）。 */
private fun readableTone(seed: Color, startTone: Double, text: Color, step: Double): Color {
    var tone = startTone
    var color = withTone(seed, tone, SEED_CHROMA)
    var steps = 0
    while (contrastRatio(text, color) < MIN_CONTRAST && steps < MAX_TONE_STEPS) {
        tone += step
        color = withTone(seed, tone, SEED_CHROMA)
        steps++
    }
    return color
}

private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + CONTRAST_OFFSET) / (minOf(la, lb) + CONTRAST_OFFSET)
}

private const val MIN_CONTRAST = 4.6f
private const val CONTRAST_OFFSET = 0.05f
private const val MAX_TONE_STEPS = 30
private const val LIGHT_MIN_TONE = 35.0
private const val LIGHT_MAX_TONE = 48.0
private const val DARK_LIFT = 3.0
private const val DARK_MIN_TONE = 62.0
private const val DARK_MAX_TONE = 80.0
private const val ON_DARK_PRIMARY_TONE = 10.0
private const val SEED_CHROMA = 200.0
private const val SURFACE_HUE_CHROMA = 3.0
