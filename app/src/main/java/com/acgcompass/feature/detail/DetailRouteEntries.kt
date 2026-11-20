package com.acgcompass.feature.detail

import com.acgcompass.domain.model.Work

/**
 * 本地规则把 Bangumi 关联作品分为主线、可选和可跳过，不调用 AI。
 * - 主线 MAIN：前传 / 续集 / 正篇（含「本作」）。
 * - 可跳过 SKIPPABLE：总集篇 / PV / 角色歌 / 预告。
 * - 可选 OPTIONAL：其余（番外 / 外传 / 剧场版 / OVA / 其他）。
 * 排序：主线（前传→本作→续集）→ 可选 → 可跳过。
 */
internal fun buildRouteEntries(
    current: Work,
    relations: List<com.acgcompass.domain.detail.DetailRelation>,
): List<RouteEntryUi> {
    if (relations.isEmpty()) return emptyList()
    fun sectionOf(rel: String?): RouteSection {
        val r = rel ?: ""
        return when {
            r.contains("总集") || r.contains("PV") || r.contains("预告") || r.contains("角色歌") -> RouteSection.SKIPPABLE
            r.contains("前") || r.contains("续") || r.contains("正篇") || r.contains("主线") -> RouteSection.MAIN
            else -> RouteSection.OPTIONAL
        }
    }
    fun mainOrder(rel: String?): Int = when {
        rel?.contains("前") == true -> 0
        rel?.contains("续") == true -> 2
        else -> 1
    }
    val others = relations.mapNotNull { rel ->
        val title = rel.nameCn.ifBlank { rel.name }.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // 非动画（游戏=4 / 书籍画集=1 / 音乐=3 / 三次元=6 等）归为衍生作/原作，不进可选/可跳过。
        val section = if (rel.type != null && rel.type != 2) RouteSection.DERIVED else sectionOf(rel.relation)
        RouteEntryUi(
            workId = rel.id.toString(),
            title = title,
            relationLabel = rel.relation?.takeIf { it.isNotBlank() } ?: "关联",
            section = section,
        )
    }
    // 当前作品作为主线「本作」。
    val self =
        RouteEntryUi(
            workId = current.id,
            title = current.titles.canonical,
            relationLabel = "本作",
            section = RouteSection.MAIN,
        )
    val main = (others.filter { it.section == RouteSection.MAIN } + self)
        .sortedBy { if (it.relationLabel == "本作") 1 else mainOrder(it.relationLabel) }
    val optional = others.filter { it.section == RouteSection.OPTIONAL }
    val skippable = others.filter { it.section == RouteSection.SKIPPABLE }
    val derived = others.filter { it.section == RouteSection.DERIVED }
    return main + optional + skippable + derived
}
