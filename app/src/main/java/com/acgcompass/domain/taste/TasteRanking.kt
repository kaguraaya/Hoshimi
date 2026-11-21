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

    /** 口味画像换了（重建完成、换了匹配度分支）时发一次：页面开着就跟着重算，不用等下次打开。 */
    val profileChanges: kotlinx.coroutines.flow.Flow<Unit>
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

/**
 * 排行里折叠同一系列用的键：去掉开头的「剧场版」类前缀、括号 / 冒号后的副标题和「第 2 シリーズ」这类季数，
 * 中日文标题取空格前的系列名（至少 4 个字），再去掉季数和紧贴在末尾的序号（[RecommendSimilarity.franchiseKey]）。
 * 例：「剧场版 魔法少女小圆 [前篇]」「魔法少女小圆」、「3月のライオン 第2シリーズ」「3月のライオン」、
 * 「響け！ユーフォニアム2」「響け！ユーフォニアム」、「攻殻機動隊 S.A.C. 2nd GIG」「攻殻機動隊 STAND ALONE COMPLEX」各是同一个键。
 */
fun rankFoldKey(title: String): String {
    var t = title.trim()
    for (prefix in FOLD_PREFIXES) if (t.startsWith(prefix)) t = t.removePrefix(prefix).trim()
    val cut = t.indexOfAny(FOLD_CUTS).takeIf { it >= MIN_CUT } ?: t.length
    t = t.substring(0, cut).replace(SERIES_PATTERN, " ").trim()
    val head = t.substringBefore(' ')
    if (head != t && isSeriesHead(head)) t = head
    val key = com.acgcompass.domain.recommend.RecommendSimilarity.franchiseKey(t)
    return key.replace(ATTACHED_ORDINAL, "").trim().ifBlank { key }
}

/** 中日文的系列名（至少 [MIN_HEAD] 个字，含汉字或假名）才按空格前的部分折叠；英文开头词太常见，不这样折。 */
private fun isSeriesHead(head: String): Boolean =
    head.length >= MIN_HEAD && head.any { Character.isIdeographic(it.code) || it in '\u3040'..'\u30ff' }

/** 同一系列只留排得最靠前的一部（列表已按匹配度从高到低排好）。 */
fun List<TasteRankItem>.foldFranchises(): List<TasteRankItem> =
    distinctBy { item -> rankFoldKey(item.title).takeIf { it.length >= MIN_FOLD_KEY } ?: "#${item.subjectId}" }

private val FOLD_PREFIXES = listOf("剧场版", "劇場版", "电影", "映画", "劇場版総集編")
private val FOLD_CUTS = charArrayOf('[', '【', '（', '(', '：', ':', '～', '~', '「')
private const val MIN_FOLD_KEY = 2
private const val MIN_CUT = 3
private const val MIN_HEAD = 4
private val SERIES_PATTERN = Regex("第\\s*[0-9一二三四五六七八九十]+\\s*(シリーズ|期|季|部|章|クール|シーズン)")
private val ATTACHED_ORDINAL = Regex("(?<=[^\\d\\s])\\d{1,2}$")
