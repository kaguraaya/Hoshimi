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
    /** 那一折的预计评分（常规分支；含区间、打 9 分以上的可能）；那一折建不出评分模型时为 null。 */
    val prediction: RatingPrediction?,
    /** 保守 / 激进分支的预计评分（[branchPrediction]）；常规和没有的分支用 [prediction]。 */
    private val predictions: Map<TasteMatchBranch, RatingPrediction> = emptyMap(),
) {
    val predicted: Double? get() = prediction?.rating

    /** 这部作品在 [branch] 下的回测预计评分。 */
    fun prediction(branch: TasteMatchBranch): RatingPrediction? = predictions[branch] ?: prediction

    /** 这部作品在 [branch] 下的回测匹配度；那一折建不出匹配度模型时为 null。 */
    fun match(branch: TasteMatchBranch): Int? =
        if (components != null && scale != null) TasteFit.score(components, scale, branch) else null
}

/**
 * 详情页 / 为你排行展示用的回测值（按当前分支算好）；[prediction] 是那一折的完整预测（含分布）。
 * [updating] 为 true：画像刚重建、新的回测还没算完，这是上一次回测的结果（或评分前的画像给出的值，见 [TasteBacktest.provisional]）。
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
     * 这部作品的回测值。[rating] 是你现在的评分。改过分时这条仍然可用：回测时这部作品被留出，
     * 它的匹配度和预计评分本来就和它自己的评分无关；只是新的回测还没算完，标成「正在更新」。
     */
    fun view(subjectId: String, branch: TasteMatchBranch, rating: Int? = null): BacktestView? =
        entries[subjectId]?.let {
            val p = it.prediction(branch)
            BacktestView(
                it.match(branch),
                p?.rating,
                p?.low,
                p?.high,
                rating ?: it.rating,
                p,
                updating = updating || (rating != null && rating != it.rating),
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
         * 已评分作品此刻没有任何回测值、但回测之后会给出：第一次回测还没算完，或刚评分、也没有评分前的值可用。
         * 有旧值（含改了分的）时不算待定，先显示旧值；评过分的作品太少（不回测）或缺少作品资料（不在回测里）时为 false。
         */
        @Suppress("UNUSED_PARAMETER") // 保留评分参数，调用方不用关心改没改过分
        fun pending(state: TasteBacktest?, subjectId: String, rating: Int?): Boolean =
            state?.entries?.get(subjectId) == null && (state == null || state.updating)

        /**
         * 刚评分、画像还没重建时：画像还不知道这次的评分，这时算出的匹配度和预计评分本身就是盲测值，直接当回测值显示，
         * 标「正在更新」，重建后换成正式的回测。画像已经知道这部作品的评分、或没有评分模型时为 null。
         */
        fun beforeRating(
            subjectId: String,
            match: TasteMatchResult,
            rating: Int?,
            profile: AdvancedTasteProfile?,
        ): BacktestView? {
            if (rating == null || profile?.divergence == null || subjectId in profile.ratedSubjectScores) return null
            val p = match.prediction
            return BacktestView(
                match = match.score.takeIf { profile.tasteFit != null && match.fromRatingModel },
                predicted = p?.rating,
                low = p?.low,
                high = p?.high,
                rating = rating,
                prediction = p,
                updating = true,
            )
        }

        /**
         * 画像重建时，给刚评分、还没有回测值的作品先算一个盲测值：用**评分前的画像**（[previous]，那时还不知道这次的评分）打分。
         * [previous] 已经知道这部作品的评分（它在 [AdvancedTasteProfile.ratedSubjectScores] 里）时不算，因为那就不是盲测了。
         */
        fun provisional(
            previous: AdvancedTasteProfile?,
            samples: List<TasteSample>,
            known: Set<String>,
            compute: ComputeTasteMatchUseCase = ComputeTasteMatchUseCase(),
            tagOverrides: Map<String, TasteCategory> = emptyMap(),
        ): Map<String, BacktestEntry> {
            val profile = previous?.takeIf { it.divergence != null } ?: return emptyMap()
            return samples.asSequence()
                .filter { it.rating != null && it.subjectId !in known && it.subjectId !in profile.ratedSubjectScores }
                .mapNotNull { s -> entryFor(s, profile, compute, tagOverrides)?.let { s.subjectId to it } }
                .toMap()
        }

        /** 用 [profile]（不知道 [sample] 的评分）给这部作品算回测值：匹配度部件、各分支的预计评分。 */
        internal fun entryFor(
            sample: TasteSample,
            profile: AdvancedTasteProfile,
            compute: ComputeTasteMatchUseCase,
            tagOverrides: Map<String, TasteCategory>,
        ): BacktestEntry? {
            val rating = sample.rating ?: return null
            val standard = profile.copy(matchBranch = TasteMatchBranch.STANDARD)
            val r =
                compute(
                    sample.feature,
                    standard,
                    tagOverrides = tagOverrides,
                    withNeighbors = false,
                    withReasons = false,
                )
            val model = profile.divergence
            val byBranch = if (model == null || r.prediction == null) {
                emptyMap()
            } else {
                TasteMatchBranch.entries.filter { it.adjustsRating }
                    .associateWith { branchPrediction(model, sample.feature, it, r.analogy, r.prediction) }
            }
            return BacktestEntry(
                subjectId = sample.subjectId,
                rating = rating,
                components = TasteFit.components(r),
                scale = profile.tasteFit,
                prediction = r.prediction,
                predictions = byBranch,
            )
        }

        /** 已评分作品少于这么多时不回测（每一折太小，结果没有意义）。 */
        const val MIN_RATED = 15
        private const val MIN_PAIRS = 8

        /**
         * 按 [inputs] 分折回测。[beforeFold] 在每折开始前调用（调用方可以在这里检查取消、让出线程）；
         * [folds] 是分折方式，默认按作品编号散列分 5 折（[TasteSelfCheck.folds]）。已评分作品太少时返回空结果。
         * 含 [priority] 里作品（刚评分 / 改了分）的那几折先算，每算完一折调用 [onFold]（到目前为止的结果），
         * 调用方可以先把这几部作品的新回测值显示出来。
         */
        @Suppress("LongParameterList") // 回测的输入、两个用例、分折方式和两个回调
        fun run(
            inputs: TasteBuildInputs,
            builder: BuildTasteProfileUseCase = BuildTasteProfileUseCase(),
            compute: ComputeTasteMatchUseCase = ComputeTasteMatchUseCase(),
            folds: (List<TasteSample>) -> List<TasteSelfCheck.Fold> = { TasteSelfCheck.folds(it) },
            priority: Set<String> = emptySet(),
            onFold: (Map<String, BacktestEntry>) -> Unit = {},
            beforeFold: () -> Unit = {},
        ): TasteBacktest {
            val rated = inputs.samples.filter { it.rating != null }
            if (rated.size < MIN_RATED) return TasteBacktest(emptyMap())
            val ratedIds = rated.mapTo(HashSet()) { it.subjectId }
            val unrated = inputs.samples.filter { it.rating == null }
            val entries = LinkedHashMap<String, BacktestEntry>()
            val ordered = folds(rated).sortedByDescending { f -> f.test.count { it.subjectId in priority } }
            for (fold in ordered) {
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
                    entryFor(t, profile, compute, inputs.tagOverrides)?.let { entries[t.subjectId] = it }
                }
                onFold(entries.toMap())
            }
            return TasteBacktest(entries)
        }
    }
}
