package com.acgcompass.domain.matching

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.WorkMatch

/**
 * 媒介类型优先级；动画管理场景默认优先动画，数值越小优先级越高：
 * 动画（含 TV / OVA / Movie）> 游戏 / 视觉小说 > 漫画 / 小说。
 *
 * 仅用于**同置信度内**的次序裁决——确保输入番剧名时不会因为顺序问题静默落到漫画 / 小说版本
 * 实现为便于单元测试的纯函数。
 */
fun mediaTypePriority(type: MediaType): Int = when (type) {
    MediaType.ANIME -> 0
    MediaType.GAME -> 1
    MediaType.VN -> 1
    MediaType.MANGA -> 2
    MediaType.NOVEL -> 2
    // 音乐、真人和未知类型优先级最低，同置信度时排在最后。
    MediaType.OTHER -> 3
}

/**
 * 按置信度降序和媒介类型优先级对多源匹配排序。
 *
 * 同名作品存在多种媒介类型且置信度接近时，动画版本排在漫画 / 小说版本之前，避免一键加入静默落到
 * 错误类型。置信度差异显著时仍以置信度为准（不强行把低置信动画顶到高置信其它类型之上）。
 */
fun sortMatchesByTypePriority(matches: List<WorkMatch>): List<WorkMatch> = matches.sortedWith(
    compareByDescending<WorkMatch> { it.matchConfidence }
        .thenBy { mediaTypePriority(it.work.mediaType) },
)

/**
 * 判断同名候选是否存在多种媒介类型且置信度接近；命中时应要求用户选择类型。
 * 相同条件下保持稳定顺序。
 *
 * @param matches 已按置信度排序的候选。
 * @param threshold 置信度接近阈值（默认 0.08）；最高分候选与其它候选差值在该阈值内视为「接近」。
 * @return 当存在不同媒介类型且彼此置信度接近时为 `true`。
 */
fun hasAmbiguousMediaType(matches: List<WorkMatch>, threshold: Float = 0.08f): Boolean {
    if (matches.size < 2) return false
    val top = matches.maxByOrNull { it.matchConfidence } ?: return false
    val close = matches.filter { top.matchConfidence - it.matchConfidence <= threshold }
    return close.map { it.work.mediaType }.distinct().size > 1
}
