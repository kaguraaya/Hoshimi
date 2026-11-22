package com.acgcompass.domain.taste

import kotlin.math.abs
import kotlin.math.sqrt

/** 预测账本的记录场景：用户在哪里看到了这部作品的预测。 */
enum class PredictionContext {
    /** 打开作品详情页。 */
    DETAIL,

    /** 探索队列里这张卡片出现在眼前。 */
    EXPLORE,
}

/** 账本里的模型版本名：配置不同就是不同版本，互不覆盖。 */
object TasteModelVersion {
    fun of(options: DivergenceOptions): String = buildString {
        append(if (options.perUserGate) "gate3" else "legacy1")
        if (options.scale == ScaleMode.QUANTILE) append("-q")
        if (options.recencyHalfLifeDays > 0.0) append("-r")
        if (options.controversyAxis) append("-c")
    }

    /** 匹配度部分的版本：刻度方法 + 分支（`fit4` = 锚定刻度、圈子分封顶、9 分以上可能的上尾；没有匹配度模型时是旧公式）。 */
    fun match(profile: AdvancedTasteProfile): String =
        (if (profile.tasteFit != null) "fit4-" else "formula-") + profile.matchBranch.key

    /**
     * 预测账本里的完整版本：评分模型、匹配度刻度、分支分开写（`gate2|fit4-standard`），换了其中任何一个，
     * 新旧记录都能分开对账。[model] 默认是画像的主评分模型；记对照模型时传对照模型。
     */
    fun ledger(profile: AdvancedTasteProfile, model: PersonalDivergenceModel? = profile.divergence): String? =
        model?.let { "${it.modelVersion}|${match(profile)}" }
}

/** 一条有了实际评分的事前预测。 */
data class LedgerEntry(
    val workId: String,
    val modelVersion: String,
    val context: PredictionContext?,
    val predicted: Double?,
    val low: Double?,
    val high: Double?,
    val matchScore: Int,
    val actual: Int,
    val profileSize: Int,
)

/** 一个模型版本在账本上的事前表现。 */
data class LedgerVersionStats(
    val modelVersion: String,
    /** 有预计评分、也有实际评分的作品数。 */
    val count: Int,
    val mae: Double?,
    val rmse: Double?,
    /** 实际评分落在预测区间里的比例；这个版本没有区间时为 `null`。 */
    val intervalCoverage: Double?,
    /** 区间平均宽度（10 分制）。 */
    val intervalWidth: Double?,
    /** 预计评分和实际评分的 Spearman；作品太少时为 `null`。 */
    val spearman: Double?,
)

/** 两个版本在同一批作品上的对比（只算两边都有记录的作品）。 */
data class LedgerPairedComparison(
    val baseVersion: String,
    val otherVersion: String,
    val count: Int,
    /** 其它版本 − 基准版本的平均绝对误差：负数表示其它版本更准。 */
    val maeDiff: Double?,
    /** 其它版本误差更小的作品占比。 */
    val otherBetterShare: Double?,
)

/** 账本对账：纯计算，不读数据库。 */
object PredictionLedgerReport {
    private const val MIN_RANK_SAMPLES = 5

    fun byVersion(entries: List<LedgerEntry>): List<LedgerVersionStats> =
        entries.groupBy { it.modelVersion }.map { (version, rows) -> stats(version, rows) }
            .sortedByDescending { it.count }

    fun paired(entries: List<LedgerEntry>, baseVersion: String, otherVersion: String): LedgerPairedComparison {
        val base = entries.filter { it.modelVersion == baseVersion && it.predicted != null }.associateBy { it.workId }
        val other = entries.filter { it.modelVersion == otherVersion && it.predicted != null }.associateBy { it.workId }
        val common = base.keys intersect other.keys
        if (common.isEmpty()) return LedgerPairedComparison(baseVersion, otherVersion, 0, null, null)
        var diffSum = 0.0
        var better = 0
        for (id in common) {
            val b = base.getValue(id)
            val o = other.getValue(id)
            val errB = abs(b.predicted!! - b.actual)
            val errO = abs(o.predicted!! - o.actual)
            diffSum += errO - errB
            if (errO < errB) better++
        }
        return LedgerPairedComparison(
            baseVersion,
            otherVersion,
            common.size,
            diffSum / common.size,
            better.toDouble() / common.size,
        )
    }

    private fun stats(version: String, rows: List<LedgerEntry>): LedgerVersionStats {
        val withPred = rows.filter { it.predicted != null }
        if (withPred.isEmpty()) return LedgerVersionStats(version, 0, null, null, null, null, null)
        val errors = withPred.map { it.predicted!! - it.actual }
        val withInterval = withPred.filter { it.low != null && it.high != null }
        return LedgerVersionStats(
            modelVersion = version,
            count = withPred.size,
            mae = errors.map(::abs).average(),
            rmse = sqrt(errors.map { it * it }.average()),
            intervalCoverage = withInterval.takeIf { it.isNotEmpty() }
                ?.count { it.actual.toDouble() in it.low!!..it.high!! }
                ?.toDouble()?.div(withInterval.size),
            intervalWidth = withInterval.takeIf { it.isNotEmpty() }?.map { it.high!! - it.low!! }?.average(),
            spearman = if (withPred.size >= MIN_RANK_SAMPLES) {
                spearman(withPred.map { it.predicted!! }, withPred.map { it.actual.toDouble() })
            } else {
                null
            },
        )
    }

    internal fun spearman(a: List<Double>, b: List<Double>): Double? {
        val ra = ranks(a)
        val rb = ranks(b)
        val ma = ra.average()
        val mb = rb.average()
        var num = 0.0
        var da = 0.0
        var db = 0.0
        for (i in ra.indices) {
            num += (ra[i] - ma) * (rb[i] - mb)
            da += (ra[i] - ma) * (ra[i] - ma)
            db += (rb[i] - mb) * (rb[i] - mb)
        }
        if (da <= 0.0 || db <= 0.0) return null
        return num / sqrt(da * db)
    }

    /** 平均秩（并列取平均）。 */
    private fun ranks(values: List<Double>): DoubleArray {
        val order = values.indices.sortedBy { values[it] }
        val out = DoubleArray(values.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && values[order[j + 1]] == values[order[i]]) j++
            val rank = (i + j) / 2.0 + 1.0
            for (k in i..j) out[order[k]] = rank
            i = j + 1
        }
        return out
    }
}
