package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Bangumi15M 评测（直接调用 App 的画像构建与打分代码，离线指标由 `docs/eval/report_b15m.py` 统计）。
 *
 * 两种切分，数据文件不存在时对应用例自动跳过（数据不进仓库，生成方法见 `docs/algorithm-eval.md`）：
 * - **时间切分**（`build/loo/bangumi15m-temporal.json`，主报告）：切点之前的评分建画像，预测切点之后的评分；
 *   社区分、人数、评分分布按切点之前的评分重新计算，不用之后的数据；
 * - **随机 5 折**（`build/loo/bangumi15m-eval.json`，与历史结果对照）：每位用户的评分随机分 5 折轮流留出。
 *
 * 每个候选方案（[EvalVariants]）在同一份切分上各跑一遍，逐条写出预测，配对比较。
 * 时间戳统一按毫秒处理：旧数据文件里的秒级时间戳会自动换算。
 */
class TasteEvalHarnessTest : StringSpec({

    val temporalFile = File("build/loo/bangumi15m-temporal.json")
    val randomFile = File("build/loo/bangumi15m-eval.json")
    val variantFilter = System.getProperty("eval.variants")?.split(',')?.map { it.trim() }?.toSet()
    val splits = System.getProperty("eval.splits")?.split(',')?.map { it.trim() }?.toSet()
    val variants by lazy { EvalVariants.all(temporalFile, variantFilter ?: EvalVariants.DEFAULT_NAMES) }

    "temporal split: train before the cutoff, predict after it"
        .config(enabled = temporalFile.exists() && (splits == null || "temporal" in splits)) {
            val data = EvalData.load(temporalFile)
            val cutoff = requireNotNull(data.cutoffMs) { "temporal file needs cutoff_ms" }
            val out = EvalWriter(File("build/loo/eval-temporal.tsv"), variants)
            for ((userIndex, user) in data.users.withIndex()) {
                val train = user.filter { (it.updatedAtMillis ?: 0L) < cutoff }
                val test = user.filter { (it.updatedAtMillis ?: 0L) >= cutoff }
                if (train.size < MIN_TRAIN || test.isEmpty()) continue
                out.writeUser(userIndex, train, test, data.poolFor(train), cutoff)
            }
            out.close()
            (out.rows > 0) shouldBe true
        }

    "random 5-fold split (historical comparison)"
        .config(enabled = randomFile.exists() && (splits == null || "random" in splits)) {
            val data = EvalData.load(randomFile)
            val out = EvalWriter(File("build/loo/eval-random.tsv"), variants)
            for ((userIndex, raw) in data.users.withIndex()) {
                val user = raw.shuffled(Random(userIndex))
                val now = user.maxOf { it.updatedAtMillis ?: 0L }
                for (fold in 0 until FOLDS) {
                    val test = user.filterIndexed { i, _ -> i % FOLDS == fold }
                    val train = user.filterIndexed { i, _ -> i % FOLDS != fold }
                    out.writeUser(userIndex, train, test, data.poolFor(train), now, fold)
                }
            }
            out.close()
            (out.rows > 0) shouldBe true
        }
}) {
    private companion object {
        const val FOLDS = 5
        const val MIN_TRAIN = 10
    }
}

/** 一个候选方案：怎样从训练样本建画像。 */
internal data class EvalVariant(
    val name: String,
    val build: (train: List<TasteSample>, now: Long, pool: List<WorkFeature>) -> AdvancedTasteProfile,
)

internal object EvalVariants {
    private val builder = BuildTasteProfileUseCase()

    private fun variant(name: String, options: DivergenceOptions) = EvalVariant(name) { train, now, pool ->
        builder(train, now, pool, divergenceOptions = options)
    }

    /** 稳定性对照：同一方案，训练集里按编号散列挑一部已评作品拿掉，看其余预测变多少。 */
    private fun dropOne(name: String, options: DivergenceOptions) = EvalVariant(name) { train, now, pool ->
        val drop = train.filter { it.rating != null }.minByOrNull {
            TasteFit.stableHash(
                it.subjectId + "#drop",
            )
        }?.subjectId
        builder(train.filter { it.subjectId != drop }, now, pool, divergenceOptions = options)
    }

    /** 不指定 `-Peval.variants` 时只跑这两个：上一个发布版和 App 当前默认。 */
    val DEFAULT_NAMES = setOf("legacy", "default")

    /**
     * 候选方案。`learned` 会现场重新学一遍全局先验（只用时间评测集切点之前的评分：对每位用户做内部 5 折，
     * 把所有折外结果合在一起求一组权重），用来核对 [ExpertWeights.LEARNED]；其余方案直接用写死的常量。
     */
    fun all(temporalFile: java.io.File, names: Set<String>): List<EvalVariant> {
        val gate = DivergenceOptions(perUserGate = true)
        val candidates = linkedMapOf<String, () -> EvalVariant>(
            "legacy" to { EvalVariant("legacy") { train, now, pool -> builder(train, now, pool) } },
            "default" to { variant("default", DivergenceOptions.DEFAULT) },
            "gate" to { variant("gate", gate) },
            "gateLq" to { variant("gateLq", DivergenceOptions.DEFAULT.copy(scale = ScaleMode.QUANTILE)) },
            "gateLr" to { variant("gateLr", DivergenceOptions.DEFAULT.copy(recencyHalfLifeDays = RECENCY_DAYS)) },
            "gateLc" to { variant("gateLc", DivergenceOptions.DEFAULT.copy(controversyAxis = true)) },
            "gateS" to { variant("gateS", DivergenceOptions.DEFAULT.copy(seriesPrefix = true)) },
            "gateShuffle" to { variant("gateShuffle", DivergenceOptions.DEFAULT.copy(stableFolds = false)) },
            "gateR3" to { variant("gateR3", DivergenceOptions.DEFAULT.copy(innerRepeats = 3)) },
            "defaultD" to { dropOne("defaultD", DivergenceOptions.DEFAULT) },
            "gateShuffleD" to { dropOne("gateShuffleD", DivergenceOptions.DEFAULT.copy(stableFolds = false)) },
            "gateR3D" to { dropOne("gateR3D", DivergenceOptions.DEFAULT.copy(innerRepeats = 3)) },
            "learned" to {
                val learned = if (temporalFile.exists()) learnPrior(temporalFile, gate) else ExpertWeights.LEGACY
                println("learned gate prior: $learned")
                variant("learned", gate.copy(gatePrior = learned))
            },
        )
        return candidates.filterKeys { it in names }.values.map { it() }
    }

    private const val RECENCY_DAYS = 730.0

    fun learnPrior(temporalFile: java.io.File, options: DivergenceOptions): ExpertWeights {
        val data = EvalData.load(temporalFile)
        val cutoff = data.cutoffMs ?: return ExpertWeights.LEGACY
        val rows = ArrayList<DoubleArray>()
        val targets = ArrayList<Double>()
        for (user in data.users) {
            val train = user.filter { (it.updatedAtMillis ?: 0L) < cutoff }
            val eligible = train.filter { it.rating != null && (it.feature.bangumiScore ?: 0f) > 0f }
                .sortedByDescending { it.updatedAtMillis ?: 0L }
            val (r, y) = PersonalDivergenceModel.innerCvRows(
                eligible,
                PersonalDivergenceModel.tagIdf(train, data.poolFor(train)),
                emptyMap(),
                PersonalDivergenceModel.COMMUNITY_VOTE_PRIOR,
                options,
            )
            rows += r
            targets += y.toList()
        }
        // 几乎不加先验地在全部折外样本上求解：得到「平均而言」每个部分该给多少权重。
        return PersonalDivergenceModel.solveGate(
            rows,
            targets.toDoubleArray(),
            options.copy(gatePriorStrength = 1e-3),
            fallbackHalfWidth = 1.0,
        ).first
    }
}

internal class EvalData(
    val subjects: Map<String, WorkFeature>,
    val users: List<List<TasteSample>>,
    private val pool: List<String>,
    val cutoffMs: Long?,
) {
    fun poolFor(train: List<TasteSample>): List<WorkFeature> {
        val rated = train.mapTo(HashSet()) { it.subjectId }
        return pool.asSequence().filter { it !in rated }.take(POOL_SIZE).mapNotNull { subjects[it] }.toList()
    }

    companion object {
        private const val POOL_SIZE = 200

        /** 小于这个值的时间戳是秒（旧数据文件），换算成毫秒。 */
        private const val SECONDS_THRESHOLD = 100_000_000_000L

        fun load(file: File): EvalData {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val subjects = root.getValue("subjects").jsonObject.mapValues { (id, el) -> feature(id, el.jsonObject) }
            val users = root.getValue("users").jsonArray.map { userEl ->
                userEl.jsonObject.getValue("items").jsonArray.mapNotNull { item ->
                    val arr = item.jsonArray
                    val id = arr[0].jsonPrimitive.content
                    val feature = subjects[id] ?: return@mapNotNull null
                    val ts = arr[2].jsonPrimitive.long.let { if (it < SECONDS_THRESHOLD) it * 1_000L else it }
                    TasteSample(id, arr[1].jsonPrimitive.int, null, ts, feature)
                }
            }
            val pool = root.getValue("pool").jsonArray.map { it.jsonPrimitive.content }
            val cutoff = root["cutoff_ms"]?.jsonPrimitive?.longOrNull
            return EvalData(subjects, users, pool, cutoff)
        }

        private fun feature(id: String, o: JsonObject): WorkFeature = WorkFeature(
            subjectId = id,
            tagCounts = o.getValue("tags").jsonArray.map {
                TagCount(it.jsonArray[0].jsonPrimitive.content, it.jsonArray[1].jsonPrimitive.int)
            },
            bangumiScore = o.getValue("score").jsonPrimitive.double.toFloat().takeIf { it > 0f },
            bangumiVotes = o.getValue("votes").jsonPrimitive.int,
            eps = o.getValue("eps").jsonPrimitive.int,
            titleVariants = listOf(
                o.getValue("name").jsonPrimitive.content,
                o.getValue("name_ja").jsonPrimitive.content,
            ).filter { it.isNotBlank() },
            airDate = o["date"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            ratingHistogram = o["hist"]?.jsonArray?.map { it.jsonPrimitive.int }.orEmpty(),
            platform = o["platform"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
        )
    }
}

/** 逐条写出每个方案的预测（同一行里并列，便于配对比较）。 */
internal class EvalWriter(file: File, private val variants: List<EvalVariant>) {
    private val compute = ComputeTasteMatchUseCase()
    private val out = file.bufferedWriter()
    var rows = 0
        private set
    private val buildNanos = LongArray(variants.size)
    private var builds = 0

    init {
        val perVariant = variants.flatMap { v -> FIELDS.map { "${it}_${v.name}" } }
        out.appendLine((HEAD + perVariant).joinToString("\t"))
    }

    @Suppress("LongParameterList") // 一位用户一次切分的全部输入
    fun writeUser(
        userIndex: Int,
        train: List<TasteSample>,
        test: List<TasteSample>,
        pool: List<WorkFeature>,
        now: Long,
        fold: Int = 0,
    ) {
        val profiles = variants.mapIndexed { i, v ->
            val start = System.nanoTime()
            v.build(train, now, pool).also { buildNanos[i] += System.nanoTime() - start }
        }
        builds++
        for (t in test) {
            val cells = ArrayList<Any>()
            cells.addAll(
                listOf(
                    userIndex,
                    fold,
                    train.size,
                    t.subjectId,
                    t.rating!!,
                    t.feature.bangumiScore ?: 0f,
                    t.feature.bangumiVotes,
                    t.updatedAtMillis ?: 0L,
                ),
            )
            for (profile in profiles) {
                cells.addAll(predictionCells(compute(t.feature, profile, withNeighbors = false, withReasons = false)))
            }
            out.appendLine(cells.joinToString("\t"))
            rows++
        }
    }

    /** 一个方案对一部作品的输出，顺序与 [FIELDS] 相同。 */
    private fun predictionCells(r: TasteMatchResult): List<Any> {
        val p = r.prediction
        val s = p?.summary
        return listOf(
            p?.rating ?: "", p?.baseline ?: "", p?.divergence ?: "",
            r.score, r.satisfaction, r.profileScore, r.analogy?.z ?: "",
            p?.low ?: "", p?.high ?: "",
            p?.location ?: "", s?.median ?: "", s?.low ?: "", s?.high ?: "", p?.highChance ?: "",
        )
    }

    fun close() {
        out.close()
        // 建画像的平均耗时（JVM 上，含评分模型拟合；手机上约慢几倍）：评估按人权重的额外计算量。
        val perBuild = variants.indices.joinToString { i ->
            "${variants[i].name} ${"%.1f".format(buildNanos[i] / 1e6 / builds.coerceAtLeast(1))} ms"
        }
        println("profile build time per user: $perBuild")
    }

    private companion object {
        val HEAD = listOf("user", "fold", "n_train", "subject", "rating", "community", "votes", "ts")
        val FIELDS = listOf(
            "pred", "base", "div", "score", "sat", "profile", "analogy", "low", "high",
            "loc", "med", "qlo", "qhi", "p9",
        )
    }
}
