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
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 设置 / 「我的」里各入口行的图标（放在圆形图标底里）。按标题里的关键词挑：
 * Bangumi → 电视（番组）· AniList → 地球语言（国际站）· Tenrai / Jikan → 公共网络 · MyAnimeList → 书签 ·
 * VNDB → 翻开的书（视觉小说）· AI 提供商 → 闪光 · 缓存管理 → 清扫 · 推荐与口味 → 调节 · 同步 → 同步 ·
 * 备份与导出 → 保存 · 隐私 → 盾牌 · 后台任务 → 时钟；认不出的用拼图。
 */
fun entryIconFor(title: String): ImageVector {
    val t = title.lowercase()
    if (t == "mal") return Icons.Outlined.BookmarkBorder
    return ENTRY_ICONS.firstOrNull { (keys, _) -> keys.any { it in t } }?.second ?: Icons.Outlined.Extension
}

/** 关键词 → 图标，按顺序匹配第一个命中的。 */
private val ENTRY_ICONS: List<Pair<List<String>, ImageVector>> = listOf(
    listOf("bangumi") to Icons.Outlined.LiveTv,
    listOf("anilist") to Icons.Outlined.Language,
    listOf("tenrai", "jikan") to Icons.Outlined.Public,
    listOf("myanimelist") to Icons.Outlined.BookmarkBorder,
    listOf("vndb") to Icons.Outlined.AutoStories,
    listOf("ai") to Icons.Outlined.AutoAwesome,
    listOf("缓存") to Icons.Outlined.CleaningServices,
    listOf("推荐", "口味") to Icons.Outlined.Tune,
    listOf("同步") to Icons.Outlined.Sync,
    listOf("备份", "导出") to Icons.Outlined.SaveAlt,
    listOf("隐私") to Icons.Outlined.Shield,
    listOf("后台任务") to Icons.Outlined.Schedule,
)
