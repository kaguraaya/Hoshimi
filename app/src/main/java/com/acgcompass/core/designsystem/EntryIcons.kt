package com.acgcompass.core.designsystem

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 设置 / 「我的」里各入口行的图标（放在圆形图标底里）。按标题里的关键词挑：
 * Bangumi → 电视（番组）· AniList → 地球语言（国际站）· Tenrai / Jikan → 公共网络 · MyAnimeList → 书签 ·
 * VNDB → 翻开的书（视觉小说）· AI 提供商 → 闪光 · 缓存管理 → 清扫 · 隐私 → 盾牌；认不出的用拼图。
 */
fun entryIconFor(title: String): ImageVector {
    val t = title.lowercase()
    return when {
        "bangumi" in t -> Icons.Outlined.LiveTv
        "anilist" in t -> Icons.Outlined.Language
        "tenrai" in t || "jikan" in t -> Icons.Outlined.Public
        "myanimelist" in t || "mal" == t -> Icons.Outlined.BookmarkBorder
        "vndb" in t -> Icons.Outlined.AutoStories
        "ai" in t -> Icons.Outlined.AutoAwesome
        "缓存" in t -> Icons.Outlined.CleaningServices
        "隐私" in t -> Icons.Outlined.Shield
        else -> Icons.Outlined.Extension
    }
}
