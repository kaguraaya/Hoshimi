package com.acgcompass.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
internal fun withTone(color: Color, tone: Double, maxChroma: Double): Color {
    val lab = Lab.from(color)
    val chroma = hypot(lab.a, lab.b)
    val scale = if (chroma <= 0.0) 0.0 else min(1.0, maxChroma / chroma)
    return Lab(tone, lab.a * scale, lab.b * scale).toColor()
}

internal fun toneOf(color: Color): Double = Lab.from(color).l

/**
 * CIELAB（D65 白点）与 sRGB 互转。纯 Kotlin 实现，不依赖 android.graphics，单元测试里也能用。
 * 超出 sRGB 色域的结果按通道截断。
 */
@Suppress("MagicNumber") // sRGB ↔ XYZ 的标准转换矩阵（IEC 61966-2-1），数值本身就是规范
private data class Lab(val l: Double, val a: Double, val b: Double) {
    fun toColor(): Color {
        val fy = (l + LAB_OFFSET) / LAB_SCALE
        val fx = fy + a / LAB_A_SCALE
        val fz = fy - b / LAB_B_SCALE
        val x = WHITE_X * finv(fx)
        val y = WHITE_Y * finv(fy)
        val z = WHITE_Z * finv(fz)
        val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        val bl = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
        return Color(gamma(r).toFloat(), gamma(g).toFloat(), gamma(bl).toFloat())
    }

    companion object {
        fun from(color: Color): Lab {
            val r = linear(color.red.toDouble())
            val g = linear(color.green.toDouble())
            val b = linear(color.blue.toDouble())
            val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / WHITE_X
            val y = (0.2126729 * r + 0.7151522 * g + 0.0721750 * b) / WHITE_Y
            val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / WHITE_Z
            val fx = f(x)
            val fy = f(y)
            val fz = f(z)
            return Lab(LAB_SCALE * fy - LAB_OFFSET, LAB_A_SCALE * (fx - fy), LAB_B_SCALE * (fy - fz))
        }

        private fun f(t: Double): Double = if (t > EPSILON) Math.cbrt(t) else (KAPPA * t + LAB_OFFSET) / LAB_SCALE

        private fun finv(t: Double): Double {
            val cube = t * t * t
            return if (cube > EPSILON) cube else (LAB_SCALE * t - LAB_OFFSET) / KAPPA
        }

        private fun linear(c: Double): Double =
            if (c <= SRGB_LINEAR_LIMIT) c / SRGB_LINEAR_SLOPE else Math.pow((c + SRGB_A) / (1 + SRGB_A), SRGB_GAMMA)

        private fun gamma(c: Double): Double {
            val v = if (c <= SRGB_GAMMA_LIMIT) {
                c * SRGB_LINEAR_SLOPE
            } else {
                (1 + SRGB_A) * Math.pow(c, 1 / SRGB_GAMMA) - SRGB_A
            }
            return v.coerceIn(0.0, 1.0)
        }

        private const val WHITE_X = 0.95047
        private const val WHITE_Y = 1.0
        private const val WHITE_Z = 1.08883
        private const val EPSILON = 216.0 / 24389.0
        private const val KAPPA = 24389.0 / 27.0
        private const val LAB_SCALE = 116.0
        private const val LAB_OFFSET = 16.0
        private const val LAB_A_SCALE = 500.0
        private const val LAB_B_SCALE = 200.0
        private const val SRGB_LINEAR_LIMIT = 0.04045
        private const val SRGB_GAMMA_LIMIT = 0.0031308
        private const val SRGB_LINEAR_SLOPE = 12.92
        private const val SRGB_A = 0.055
        private const val SRGB_GAMMA = 2.4
    }
}

private const val DARK_LUMINANCE_THRESHOLD = 0.5f

/** 表面的彩度上限：近灰，只留一点点色相。 */
private const val SURFACE_CHROMA = 3.0

/** 浅色卡片（近白）的彩度上限更低：纯白旁边的带色灰会显脏。 */
private const val PAPER_CHROMA = 1.5
private const val OUTLINE_VARIANT_CHROMA = 4.0
private const val TEXT_CHROMA = 6.0
private const val CONTAINER_CHROMA = 40.0
