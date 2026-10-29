package com.acgcompass.domain.model

/** 动态取色关闭时可选择的内置配色；存储名保持稳定。 */
/**
 * 主题色预设：照 FlClash 用 iOS 的系统色（按 macOS 强调色的顺序），主色就是所选的颜色。
 * 枚举名（OCEAN / SAKURA / FOREST / AMBER 等）与保存的设置值保持一致。
 *
 * @property seed 种子色（ARGB）。浅色 / 深色下实际的主色由它按明度规则换算，见 `presetColorScheme`。
 */
@Suppress("MagicNumber") // 调色板字面量：每个值就是一个颜色
enum class ThemePreset(val label: String, val seed: Long) {
    OCEAN("蓝色", 0xFF007AFF),
    PURPLE("紫色", 0xFFCB30E0),
    SAKURA("粉色", 0xFFFF2D55),
    RED("红色", 0xFFFF383C),
    ORANGE("橙色", 0xFFFF8D28),
    AMBER("黄色", 0xFFFFCC00),
    FOREST("绿色", 0xFF34C759),
    GRAPHITE("石墨", 0xFF8E8E93),
    ;

    companion object {
        fun fromStorage(raw: String?): ThemePreset = entries.firstOrNull { it.name == raw } ?: OCEAN
    }
}
