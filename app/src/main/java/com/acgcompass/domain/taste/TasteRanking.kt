package com.acgcompass.domain.taste

import kotlinx.coroutines.flow.StateFlow

/** 本地作品库按口味匹配度排出的一部作品：特征 + 匹配结果（带推荐理由和预计评分）+ 我的评分（没评过为 null）。 */
data class LocalRankedWork(val feature: WorkFeature, val match: TasteMatchResult, val myRating: Int? = null)

/** 本地作品库的口味排行：[entries] 按匹配度从高到低；[poolSize] 是参与排序的动画数，其中 [unratedCount] 部没评过分。 */
data class LocalRanking(val entries: List<LocalRankedWork>, val poolSize: Int, val unratedCount: Int)

/** 作品形式，口味排行的筛选项。 */
enum class WorkFormat(val label: String) {
    TV("TV"),
    MOVIE("剧场版"),
    OVA("OVA"),
    WEB("WEB"),
    OTHER("其它"),
    ;

    companion object {
        /** 按 Bangumi 条目的平台字段归类。 */
        fun of(platform: String?): WorkFormat {
            val p = platform?.trim()?.lowercase().orEmpty()
            return when {
                p.isEmpty() -> OTHER
                p == "tv" || p.startsWith("tv") -> TV
                "剧场" in p || "movie" in p || "电影" in p -> MOVIE
                "ova" in p || "oad" in p -> OVA
                "web" in p -> WEB
                else -> OTHER
            }
        }
    }
}

/** 这部作品和我的关系：评过分、已经收藏（想看 / 在看 / 搁置 / 抛弃等）或在待补池里。 */
enum class RankOwnership { RATED, COLLECTED, BACKLOG }

/** 口味排行里的一行。 */
data class TasteRankItem(
    val subjectId: String,
    val title: String,
    val coverUrl: String?,
    val year: Int?,
    val format: WorkFormat,
    /** 已完结；不确定时为 null。 */
    val finished: Boolean?,
    /** 口味匹配度（5–95）。 */
    val score: Int,
    /** 预计评分（10 分制）；评分太少建不了模型时为 null。 */
    val predictedRating: Double?,
    /** 主要理由，如「命中「百合」」。 */
    val reason: String?,
    val ownership: RankOwnership?,
    /** 我的评分；没评过为 null。 */
    val myRating: Int? = null,
)

/** 一次排行的结果：[poolSize] 部动画参与排序，其中 [unratedCount] 部没评过分。 */
data class TasteRanking(
    val items: List<TasteRankItem>,
    val poolSize: Int,
    val computedAt: Long,
    val unratedCount: Int = poolSize,
)

/**
 * 口味匹配度排行：本地作品库里没评过分的作品按匹配度排序，不联网。
 * 结果按口味画像缓存，画像不变时再打开直接用缓存。
 */
interface TasteRankingRepository {
    /** [force] 为 true 时忽略缓存重新计算。还没有可用的口味画像时返回 null。 */
    suspend fun load(force: Boolean = false): TasteRanking?

    /** 上次算好的结果（内存里没有就读本地文件），不重新计算；从没算过时为 null。用来打开页面先显示。 */
    suspend fun lastResult(): TasteRanking?

    /** 作品库现状：能参与排行的动画数、已在作品库里但还缺资料（标签）的部数。 */
    suspend fun poolStatus(): LocalPoolStatus

    /** 「补充作品库」的进度；没在补充、也没有刚完成的结果时为 null。应用级任务，离开页面也会继续。 */
    val expansion: StateFlow<PoolExpansion?>

    /** 按 Bangumi 排行从上次补到的位置往后取 [target] 部存进作品库（资料随结果一起存）。已在进行时忽略。 */
    fun startExpansion(target: Int)
}

/** 作品库现状。 */
data class LocalPoolStatus(val rankable: Int, val missingFeatures: Int)

/** 「补充作品库」的进度：从排行第 [startRank] 名起，已取 [done] / [total] 部；结束后 [added] 为新增部数。 */
data class PoolExpansion(
    val startRank: Int,
    val done: Int,
    val total: Int,
    val running: Boolean,
    val added: Int? = null,
    val failed: Boolean = false,
)
