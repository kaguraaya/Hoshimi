package com.acgcompass.domain.taste

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * 圈子分：这部作品是不是你常看的那类。
 *
 * 每人一个带 L2 正则的逻辑回归：正例 = 你的全部收藏（评过分的、想看、在看、搁置；抛弃和探索里右滑算半个），
 * 反例 = 作品库里你没收藏的；特征 = 作品的前 [TAGS_PER_WORK] 个标签 + 类型（TV / 剧场版 / OVA / WEB）。
 * 没收藏过的类型（例如从不碰的国产、少儿向）在反例里很多、正例里没有，权重自然为负 ——「长期不碰」本身就是信号。
 *
 * 输出在作品库上标准化（[z]：作品库里中等的作品约为 0）。
 */
class TasteCircle internal constructor(
    private val weights: Map<String, Double>,
    private val bias: Double,
    private val mean: Double,
    private val std: Double,
) {
    fun logit(feature: WorkFeature): Double = bias + features(feature).sumOf { weights[it] ?: 0.0 }

    fun z(feature: WorkFeature): Double = (logit(feature) - mean) / std

    companion object {
        const val TAGS_PER_WORK = 15
        private const val VOCAB = 800
        private const val L2 = 1.0
        private const val ITERATIONS = 300
        private const val LEARNING_RATE = 0.05
        private const val MIN_POSITIVES = 5
        private const val MIN_STD = 1e-6
        private const val BETA1 = 0.9
        private const val BETA2 = 0.999
        private const val ADAM_EPS = 1e-8
        private const val HALF_POSITIVE = 0.5

        /** 作品的稀疏特征：标签名（去掉纯数字的年份标签）和类型。 */
        internal fun features(feature: WorkFeature): List<String> {
            val tags = feature.tagCounts.asSequence().take(TAGS_PER_WORK).map { it.name }
                .filter { it.isNotBlank() && !it.all(Char::isDigit) }
            return (tags + listOfNotNull(feature.platform?.let { "@$it" })).distinct().toList()
        }

        /** 圈子分的正例：评过分的 + 想看 / 在看 / 搁置（抛弃、探索右滑算半个），探索里左滑不算。 */
        fun fromCollection(
            rated: List<TasteSample>,
            implicitSignals: List<ImplicitSignal>,
            pool: List<WorkFeature>,
        ): TasteCircle? {
            val positives = LinkedHashMap<String, Pair<WorkFeature, Double>>()
            for (signal in implicitSignals) {
                val weight = when (signal.kind) {
                    ImplicitSignal.Kind.WISH, ImplicitSignal.Kind.WATCHING, ImplicitSignal.Kind.ON_HOLD -> 1.0
                    ImplicitSignal.Kind.DROPPED, ImplicitSignal.Kind.EXPLORE_LIKED -> HALF_POSITIVE
                    ImplicitSignal.Kind.EXPLORE_SKIPPED -> null
                } ?: continue
                positives[signal.feature.subjectId] = signal.feature to weight
            }
            rated.forEach { positives[it.subjectId] = it.feature to 1.0 }
            return fit(positives, pool)
        }

        /** [positives]：收藏的作品和权重（1 = 收藏，0.5 = 抛弃 / 右滑）；[pool]：作品库。正例太少时返回 null。 */
        fun fit(positives: Map<String, Pair<WorkFeature, Double>>, pool: List<WorkFeature>): TasteCircle? {
            val negatives = pool.filter { it.subjectId !in positives }
            if (positives.size < MIN_POSITIVES || negatives.isEmpty()) return null
            val positiveList = positives.values.toList()
            val samples = positiveList.map { features(it.first) } + negatives.map { features(it) }
            val counts = HashMap<String, Int>()
            samples.forEach { fs -> fs.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
            // 次数相同按名字排：不同设备上选出同一份词表。
            val vocab = counts.entries.sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
            )
                .take(VOCAB).map { it.key }
            val index = vocab.withIndex().associate { (i, name) -> name to i }
            val rows = samples.map { fs -> fs.mapNotNull { index[it] }.toIntArray() }
            val negWeight = positiveList.size.toDouble() / negatives.size
            val weightsPerRow =
                DoubleArray(rows.size) { i -> if (i < positiveList.size) positiveList[i].second else negWeight }
            val beta = train(rows, positiveList.size, weightsPerRow, vocab.size)
            val weights = vocab.withIndex().associate { (i, name) -> name to beta[i] }
            val raw = TasteCircle(weights, beta[vocab.size], 0.0, 1.0)
            val stat = Stat.of(negatives.map(raw::logit))
            return TasteCircle(weights, beta[vocab.size], stat.mean, stat.std.coerceAtLeast(MIN_STD))
        }

        /**
         * 带 L2 正则的加权逻辑回归，Adam 全批量梯度下降（特征稀疏，几千个样本几十毫秒）。
         * 前 [positiveCount] 行是正例；返回各特征权重，最后一个是截距。
         */
        private fun train(
            rows: List<IntArray>,
            positiveCount: Int,
            rowWeights: DoubleArray,
            features: Int,
        ): DoubleArray {
            val beta = DoubleArray(features + 1)
            val m = DoubleArray(features + 1)
            val v = DoubleArray(features + 1)
            val grad = DoubleArray(features + 1)
            for (step in 1..ITERATIONS) {
                grad.fill(0.0)
                for ((i, row) in rows.withIndex()) {
                    var z = beta[features]
                    for (j in row) z += beta[j]
                    val err = rowWeights[i] * (sigmoid(z) - if (i < positiveCount) 1.0 else 0.0)
                    for (j in row) grad[j] += err
                    grad[features] += err
                }
                for (j in 0 until features) grad[j] += 2 * L2 * beta[j]
                val c1 = 1 - Math.pow(BETA1, step.toDouble())
                val c2 = 1 - Math.pow(BETA2, step.toDouble())
                for (j in grad.indices) {
                    m[j] = BETA1 * m[j] + (1 - BETA1) * grad[j]
                    v[j] = BETA2 * v[j] + (1 - BETA2) * grad[j] * grad[j]
                    beta[j] -= LEARNING_RATE * (m[j] / c1) / (sqrt(v[j] / c2) + ADAM_EPS)
                }
            }
            return beta
        }

        private fun sigmoid(z: Double): Double = 1.0 / (1.0 + exp(-z))
    }
}
