package com.acgcompass.feature.cache

/**
 * 补充记录的来源名。`WORKER` 是「后台补充」开关对应的定时任务（应用不在前台时按约 12 小时一次补充），
 * 其余是各数据源在使用应用时的补充进度。
 */
internal fun String.toCacheSourceLabel(): String = when (this) {
    "WORKER" -> "后台定时补充"
    "BANGUMI" -> "Bangumi"
    "ANILIST" -> "AniList"
    "TENRAI" -> "Tenrai"
    "VNDB" -> "VNDB"
    "JIKAN" -> "Jikan"
    else -> this
}

/** 一个来源本次补充的结果说明；「后台任务」页也用这一份，保证两边说法一致。 */
internal fun String?.toSourceStatusText(): String = when (this) {
    "TARGET_REACHED" -> "此源本次配额已完成"
    "EXHAUSTED" -> "此源候选已耗尽"
    "RUNNING" -> "正在补充"
    "FAILED" -> "本次失败，可稍后继续"
    null -> "暂无"
    else -> this
}
