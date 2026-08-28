package com.acgcompass.core.designsystem

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.acgcompass.domain.model.ThemePreset

/**
 * Root Material 3 theme for ACG Compass (现代 Android 原生视觉).
 *
 * Behavior:
 * - Honors the system dark mode by default ([darkTheme] = [isSystemInDarkTheme]).
 * - Uses Android 12+ wallpaper-based dynamic color when available and [dynamicColor] is `true`,
 *   falling back to the hand-tuned brand [LightColors] / [DarkColors] on older devices.
 * - Applies the brand [AcgTypography] and rounded [AcgShapes].
 * - Keeps the status bar icons legible by matching their light/dark appearance to the theme.
 *
 * The public API ([darkTheme], [dynamicColor], [content]) remains backward compatible so
 * existing callers such as `MainActivity` need no changes.
 */
@Composable
fun AcgCompassTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    preset: ThemePreset = ThemePreset.OCEAN,
    /** 直接给定基础配色（跳过壁纸取色与预设），供截图测试模拟特定设备的取色结果。 */
    baseColorScheme: ColorScheme? = null,
    content: @Composable () -> Unit,
) {
    val resolved = baseColorScheme
        ?: resolveColorScheme(darkTheme = darkTheme, dynamicColor = dynamicColor, preset = preset)
    // 表面层级与近灰表面：见 Surfaces.kt（照 FlClash 的设计规范）。
    val (colorScheme, surfaces) = remember(resolved) { prismColorScheme(resolved) }

    // Match the status/navigation bar icon appearance (light vs dark) to the resolved theme so
    // edge-to-edge content stays readable in both light and dark mode. Guarded against the Compose
    // preview / inspection mode where there is no host Activity window.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AcgTypography,
        shapes = AcgShapes,
    ) {
        CompositionLocalProvider(
            LocalPrismSurfaces provides surfaces,
            LocalSurfaceLevel provides SurfaceLevel.Page,
            content = content,
        )
    }
}

/**
 * Resolves the active [ColorScheme]: dynamic color on Android 12+ (when enabled), otherwise the
 * brand fallback scheme for the current light/dark mode.
 */
@Composable
private fun resolveColorScheme(darkTheme: Boolean, dynamicColor: Boolean, preset: ThemePreset): ColorScheme = when {
    dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }

    else -> presetColorScheme(preset, darkTheme)
}
