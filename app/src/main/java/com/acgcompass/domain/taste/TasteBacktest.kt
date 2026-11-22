package com.acgcompass.domain.taste

import kotlin.math.abs

/** 建一次画像用到的全部输入；回测按同一份输入分折重建。 */
data class TasteBuildInputs(
    val samples: List<TasteSample>,
    val now: Long,
    val calibrationPool: List<WorkFeature>,
    val tagOverrides: Map<String, TasteCategory>,
    val implicitSignals: List<ImplicitSignal>,
    val options: DivergenceOptions,
)

/**
 * 一部已评分作品的回测结果：建画像时不知道这部作品（5 折里它所在那一折被留出），再给它打分。
 * 匹配度保存原始部件和那一折的刻度，换分支时直接重算，不用重新回测。
 */
class BacktestEntry(
    val subjectId: String,
    val rating: Int,
    private val components: DoubleArray?,
    private val scale: TasteFitScale?,
    /** 那一折的预计评分（含区间、打 9 分以上的可能）；那一折建不出评分模型时为 null。 */
    val prediction: RatingPrediction?,
) {
    val predicted: Double? get() = prediction?.rating

    /** 这部作品在 [branch] 下的回测匹配度；那一折建不出匹配度模型时为 null。 */
    fun match(branch: TasteMatchBranch): Int? =
        if (components != null && scale != null) TasteFit.score(components, scale, branch) else null
}

/**
 * 详情页 / 为你排行展示用的回测值（按当前分支算好）；[prediction] 是那一折的完整预测（含分布）。
 * [updating] 为 true：画像刚重建、新的回测还没算完，这是上一次回测的结果。
 */
data class BacktestView(
    val match: Int?,
    val predicted: Double?,
    val low: Double?,
    val high: Double?,
    val rating: Int,
    val prediction: RatingPrediction? = null,
    val updating: Boolean = false,
)

/**
 * 已评分作品的回测：分 [TasteSelfCheck.DEFAULT_FOLDS] 份，每次用其余几份建画像（作品库、收藏信号、标签分类和 App 一样），
 * 给留出的那份打分。详情页、为你排行里已评分作品的「回测匹配度」「回测预计评分」都来自这里——
 * 不是把你的评分换算出来的数。
 */
class TasteBacktest(val entries: Map<String, BacktestEntry>, val updating: Boolean = false) {
    /**
     * 这部作品的回测值。[rating] 是你现在的评分：和回测时的评分不一样（改过分）时这条已经过期，返回 null。
     */
    fun view(subjectId: String, branch: TasteMatchBranch, rating: Int? = null): BacktestView? =
        entries[subjectId]?.takeIf { rating == null || it.rating == rating }?.let {
            BacktestView(
                it.match(branch),
                it.predicted,
                it.prediction?.low,
                it.prediction?.high,
                it.rating,
                it.prediction,
                updating = updating,
            )
        }

    /** 画像重建后、新的回测算完前：继续用这一份，标上「正在更新」，不退回把你的评分算进去的分。 */
    fun updatingCopy(): TasteBacktest = TasteBacktest(entries, updating = true)

    /** 你的评分和口味匹配度的关联（回测，排序相关）：越高，说明口味越对的作品你打分也越高。 */
    fun coupling(branch: TasteMatchBranch): Double? {
        val pairs = entries.values.mapNotNull { e -> e.match(branch)?.let { it.toDouble() to e.rating.toDouble() } }
        return if (pairs.size < MIN_PAIRS) {
            null
        } else {
            TasteSelfCheck.spearman(
                pairs.map { it.first },
                pairs.map { it.second },
            )
        }
    }

    /** 预计评分和你实际评分的平均差（回测）。 */
    val ratingMae: Double?
        get() = entries.values.mapNotNull { e -> e.predicted?.let { abs(it - e.rating) } }
            .takeIf { it.size >= MIN_PAIRS }?.average()

    /** 预计评分和你实际评分的排序相关（回测）。 */
    val ratingSpearman: Double?
        get() {
            val pairs = entries.values.mapNotNull { e -> e.predicted?.let { it to e.rating.toDouble() } }
            return if (pairs.size < MIN_PAIRS) {
                null
            } else {
                TasteSelfCheck.spearman(
                    pairs.map { it.first },
                    pairs.map { it.second },
                )
            }
        }

    companion object {
        /**
         * 已评分作品此刻没有可用的回测值、但回测之后会给出：回测还没算完，或这部作品刚评分 / 改了分、新的回测还没算完。
         * 评过分的作品太少（不回测）或缺少作品资料（不在回测里）时为 false。
         */
        fun pending(state: TasteBacktest?, subjectId: String, rating: Int?): Boolean {
            val entry = state?.entries?.get(subjectId)
            val valid = entry != null && (rating == null || entry.rating == rating)
            return when {
                valid -> false
                state == null || state.updating -> true
                else -> entry != null
            }
        }

        /** 已评分作品少于这么多时不回测（每一折太小，结果没有意义）。 */
        const val MIN_RATED = 15
        private const val MIN_PAIRS = 8

        /**
         * 按 [inputs] 分折回测。[beforeFold] 在每折开始前调用（调用方可以在这里检查取消、让出线程）；
         * [folds] 是分折方式，默认按作品编号散列分 5 折（[TasteSelfCheck.folds]）。已评分作品太少时返回空结果。
         */
        fun run(
            inputs: TasteBuildInputs,
            builder: BuildTasteProfileUseCase = BuildTasteProfileUseCase(),
            compute: ComputeTasteMatchUseCase = ComputeTasteMatchUseCase(),
            folds: (List<TasteSample>) -> List<TasteSelfCheck.Fold> = { TasteSelfCheck.folds(it) },
            beforeFold: () -> Unit = {},
        ): TasteBacktest {
            val rated = inputs.samples.filter { it.rating != null }
            if (rated.size < MIN_RATED) return TasteBacktest(emptyMap())
            val ratedIds = rated.mapTo(HashSet()) { it.subjectId }
            val unrated = inputs.samples.filter { it.rating == null }
            val entries = LinkedHashMap<String, BacktestEntry>()
            for (fold in folds(rated)) {
                beforeFold()
                val held = fold.test.mapTo(HashSet()) { it.subjectId }
                // 留出的作品当作没看过：不进画像，也不作为收藏信号进圈子分。
                val implicit = inputs.implicitSignals.filter { it.feature.subjectId !in held }
                val profile = builder(
                    fold.train + unrated,
                    inputs.now,
                    inputs.calibrationPool.filter { it.subjectId !in ratedIds },
                    inputs.tagOverrides,
                    implicitSignals = implicit,
                    divergenceOptions = inputs.options,
                )
                for (t in fold.test) {
                    val rating = t.rating ?: continue
                    val r =
                        compute(
                            t.feature,
                            profile,
                            tagOverrides = inputs.tagOverrides,
                            withNeighbors = false,
                            withReasons = false,
                        )
                    entries[t.subjectId] = BacktestEntry(
                        subjectId = t.subjectId,
                        rating = rating,
                        components = TasteFit.components(r),
                        scale = profile.tasteFit,
                        prediction = r.prediction,
                    )
                }
            }
            return TasteBacktest(entries)
        }
    }
}
