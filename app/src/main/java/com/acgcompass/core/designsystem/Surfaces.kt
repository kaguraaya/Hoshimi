package com.acgcompass.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import kotlin.math.hypot
import kotlin.math.min

/**
 * 表面层级：用明度的台阶表达结构，而不是描边。
 *
 * 做法照搬 FlClash 的设计规范（`.agents/surfaces.md` 与 `prism_ui/lib/surface.dart`）：
 *
 * | 层级 | 浅色 | 深色 | 放什么 |
 * |---|---|---|---|
 * | [Page] | 明度 94 的灰 | 6 | 页面底 |
 * | [Card] | 98 的近白 | 15 | 卡片、成组的行 |
 * | [Block] | 94 的灰 | 21 | 卡片里的色块：未选中的选项、内嵌条目、输入框 |
 * | [Inset] | 90 | 27 | 色块里的小标记：图标底、徽章、进度轨 |
 *
 * 浅色是「灰底页面上放近白卡片，卡片里再放灰色块」；深色只往亮处走（更暗的一级会像一个洞）。
 * 每个表面取它所在表面的下一级，由 [SurfaceScope] 沿组件树传下去——同一个组件放在页面上是白的、
 * 放进卡片里是灰的，调用处不需要自己挑颜色。
 */
enum class SurfaceLevel {
    Page,
    Card,
    Block,
    Inset,
    ;

    /** 再深一级；[Inset] 是最底层，没有更深的一级。 */
    val next: SurfaceLevel
        get() = when (this) {
            Page -> Card
            Card -> Block
            Block, Inset -> Inset
        }
}

/** 四个层级的实际颜色，随主题提供。 */
data class PrismSurfaces(val page: Color, val card: Color, val block: Color, val inset: Color) {
    fun colorOf(level: SurfaceLevel): Color = when (level) {
        SurfaceLevel.Page -> page
        SurfaceLevel.Card -> card
        SurfaceLevel.Block -> block
        SurfaceLevel.Inset -> inset
    }
}

internal val LocalPrismSurfaces = compositionLocalOf<PrismSurfaces?> { null }
internal val LocalSurfaceLevel = compositionLocalOf { SurfaceLevel.Page }

/** 当前所在的表面层级与取色入口。 */
object Surfaces {
    val level: SurfaceLevel
        @Composable @ReadOnlyComposable
        get() = LocalSurfaceLevel.current

    /** 放在当前表面上的东西应取的层级（深一级）。 */
    val raised: SurfaceLevel
        @Composable @ReadOnlyComposable
        get() = LocalSurfaceLevel.current.next

    @Composable
    @ReadOnlyComposable
    fun colorOf(level: SurfaceLevel): Color {
        val surfaces = LocalPrismSurfaces.current
        if (surfaces != null) return surfaces.colorOf(level)
        // 没有经过 AcgCompassTheme（如个别预览）时退回 Material 的容器色。
        val scheme = MaterialTheme.colorScheme
        return when (level) {
            SurfaceLevel.Page -> scheme.surface
            SurfaceLevel.Card -> scheme.surfaceContainerLow
            SurfaceLevel.Block -> scheme.surfaceContainerHigh
            SurfaceLevel.Inset -> scheme.surfaceContainerHighest
        }
    }

    /** 当前表面上「抬起一级」的填充色：卡片、选项、色块都用它。 */
    val raisedColor: Color
        @Composable @ReadOnlyComposable
        get() = colorOf(raised)

    /**
     * 成组的行之间的发丝线：`onSurface` 的 14%。半透明的线在页面、卡片上对比度一致，
     * 也不带主题色偏（`outlineVariant` 在深色页面上会显眼一倍）。
     */
    val hairline: Color
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.colorScheme.onSurface.copy(alpha = HAIRLINE_ALPHA)

    private const val HAIRLINE_ALPHA = 0.14f
}

/** 声明 [content] 画在 [level] 这一级表面上，里面的组件据此取下一级的颜色。 */
@Composable
fun SurfaceScope(level: SurfaceLevel, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSurfaceLevel provides level, content = content)
}

/**
 * 把一套 Material 配色（品牌预设或壁纸取色）改写成上面的层级体系。
 *
 * - **表面保持近灰**：保留原配色的色相，但把彩度压到很低。壁纸取色会把整页染成壁纸的颜色，
 *   卡片成了一整块重色；压低后颜色只留给「被选中、开启、出错、主操作」。
 * - **主色容器取主色的色相**，浅色明度 90、深色 30，彩度封顶——壁纸取色下它可能是一块刺眼的纯色。
 * - **次 / 第三容器色用中性色**：它们既不当表面也不当选中态，色调按钮、导航指示器因此是中性的。
 * - 深色下 `onSurface` 提到 97，标题不比浅色弱。
 */
@Suppress("LongMethod", "MagicNumber") // 一张角色 → 明度的对照表：数值就是规范本身，拆成常量反而难核对
internal fun prismColorScheme(base: ColorScheme): Pair<ColorScheme, PrismSurfaces> {
    val dark = base.surface.luminance() < DARK_LUMINANCE_THRESHOLD
    val hue = base.surface
    fun gray(tone: Double, chroma: Double = SURFACE_CHROMA) = withTone(hue, tone, chroma)

    val surfaces = if (dark) {
        PrismSurfaces(page = gray(6.0), card = gray(15.0), block = gray(21.0), inset = gray(27.0))
    } else {
        PrismSurfaces(
            page = gray(94.0),
            card = gray(98.0, PAPER_CHROMA),
            block = gray(94.0),
            inset = gray(90.0),
        )
    }
    val containerTone = if (dark) 30.0 else 90.0
    val onContainerTone = if (dark) 90.0 else 30.0
    // Material 自带组件各自取哪个角色是固定的，这里按「它应该落在哪一级」来填：
    // 浮在内容上的（菜单、对话框、底部栏、底部面板）浅色取卡片级、深色取色块级。
    val float = if (dark) surfaces.block else surfaces.card
    val scheme = base.copy(
        background = surfaces.page,
        surface = surfaces.page,
        surfaceDim = if (dark) surfaces.page else gray(88.0),
        surfaceBright = if (dark) surfaces.inset else surfaces.card,
        surfaceContainerLowest = if (dark) gray(4.0) else gray(99.0, PAPER_CHROMA),
        surfaceContainerLow = surfaces.card,
        surfaceContainer = float,
        surfaceContainerHigh = float,
        surfaceContainerHighest = surfaces.inset,
        surfaceVariant = surfaces.inset,
        onSurface = if (dark) gray(97.0) else withTone(base.onSurface, toneOf(base.onSurface), TEXT_CHROMA),
        onBackground = if (dark) gray(97.0) else withTone(base.onBackground, toneOf(base.onBackground), TEXT_CHROMA),
        // 次要文字与描边线同样收敛彩度：壁纸取色下它们会带明显的色偏，和近灰的表面不搭。
        onSurfaceVariant = withTone(base.onSurfaceVariant, toneOf(base.onSurfaceVariant), TEXT_CHROMA),
        outline = withTone(base.outline, toneOf(base.outline), TEXT_CHROMA),
        outlineVariant = withTone(base.outlineVariant, toneOf(base.outlineVariant), OUTLINE_VARIANT_CHROMA),
        primaryContainer = withTone(base.primary, containerTone, CONTAINER_CHROMA),
        onPrimaryContainer = withTone(base.primary, onContainerTone, CONTAINER_CHROMA),
        secondaryContainer = surfaces.inset,
        onSecondaryContainer = if (dark) gray(97.0) else withTone(base.onSurface, toneOf(base.onSurface), TEXT_CHROMA),
        tertiaryContainer = surfaces.inset,
        onTertiaryContainer = if (dark) gray(97.0) else withTone(base.onSurface, toneOf(base.onSurface), TEXT_CHROMA),
        surfaceTint = Color.Transparent,
    )
    return scheme to surfaces
}

/** 保留 [color] 的色相，把明度设为 [tone]（CIE L*，0–100），彩度不超过 [maxChroma]。 */
private fun withTone(color: Color, tone: Double, maxChroma: Double): Color {
    val lab = DoubleArray(LAB_COMPONENTS)
    ColorUtils.colorToLAB(color.toArgb(), lab)
    val chroma = hypot(lab[1], lab[2])
    val scale = if (chroma <= 0.0) 0.0 else min(1.0, maxChroma / chroma)
    return Color(ColorUtils.LABToColor(tone, lab[1] * scale, lab[2] * scale))
}

private fun toneOf(color: Color): Double {
    val lab = DoubleArray(LAB_COMPONENTS)
    ColorUtils.colorToLAB(color.toArgb(), lab)
    return lab[0]
}

private const val LAB_COMPONENTS = 3
private const val DARK_LUMINANCE_THRESHOLD = 0.5f

/** 表面的彩度上限：近灰，只留一点点色相。 */
private const val SURFACE_CHROMA = 3.0

/** 浅色卡片（近白）的彩度上限更低：纯白旁边的带色灰会显脏。 */
private const val PAPER_CHROMA = 1.5
private const val OUTLINE_VARIANT_CHROMA = 4.0
private const val TEXT_CHROMA = 6.0
private const val CONTAINER_CHROMA = 40.0
