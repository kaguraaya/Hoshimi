package com.acgcompass.domain.model

/** 动态取色关闭时可选择的内置配色；存储名保持稳定。 */
enum class ThemePreset(val label: String) {
    OCEAN("星海蓝"),
    SAKURA("樱绯"),
    FOREST("青森"),
    AMBER("琥珀"),
    ;

    companion object {
        fun fromStorage(raw: String?): ThemePreset = entries.firstOrNull { it.name == raw } ?: OCEAN
    }
}
