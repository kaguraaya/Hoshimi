package com.acgcompass.domain.usecase

import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.checkAll

/**
 *
 * 校验 [AggregateRatingsUseCase] 的「不伪造、缺失即标记、样本不足不下结论」契约：对任意多源
 * 评分输入（含缺失 null、有效分、无效分混合）——
 *
 * 1. 输出 [com.acgcompass.domain.model.RatingAggregate.perSource] 键集合与输入完全一致；
 *    任何输入为 `null`（缺失）的源在输出中仍为 `null`，**绝不**被其它源数据回填。
 * 2. 每个非空输出条目与对应输入条目是同一对象（`===`），即未被任何其它源数据替换 / 伪造。
 * 3. 当有效评分样本数（同一有效性规则：`>0 且 <= 该源满分`）< [AggregateRatingsUseCase.MIN_VALID_SAMPLES]
 *    时 `consensus == null`（不下结论）；否则 `consensus != null` 且其各字段落在 `[0,1]`。
 *
 */
@OptIn(ExperimentalKotest::class)
class AggregateRatingsNoFabricationPropertyTest : StringSpec({

    val useCase = AggregateRatingsUseCase()

    // 该源量纲满分（与 isValidScore / normalizeToTen 一致）。
    fun maxFor(source: SourceId): Float = when (source) {
        SourceId.BANGUMI, SourceId.TENRAI, SourceId.JIKAN, SourceId.MAL ->
            AggregateRatingsUseCase.TEN_SCALE_MAX
        SourceId.ANILIST, SourceId.VNDB -> AggregateRatingsUseCase.HUNDRED_SCALE_MAX
    }

    // 有效分：落在 (0, 满分] 内的有限正值（用整数 / 10 生成，避免浮点边界抖动）。
    fun validScoreArb(source: SourceId): Arb<Float> {
        return Arb.int(1..(maxFor(source) * 10f).toInt())
            .map { it / 10f }
    }

    // 无效分：0 / 负数 / 越界 / NaN / 无穷——均应被判为「暂无有效评分」，不参与共识。
    fun invalidScoreArb(source: SourceId): Arb<Float> = Arb.of(
        0f,
        -1f,
        -42.5f,
        maxFor(source) + 0.1f,
        maxFor(source) + 50f,
        Float.NaN,
        Float.POSITIVE_INFINITY,
        Float.NEGATIVE_INFINITY,
    )

    // 单源取值：缺失(null) / 有效条目 / 无效条目 三选一。
    fun entryArb(source: SourceId): Arb<RatingEntry?> = Arb.choice(
        Arb.of<RatingEntry?>(listOf(null)),
        Arb.bind(validScoreArb(source), Arb.int(0..100_000)) { s, v -> RatingEntry(score = s, voteCount = v) },
        Arb.bind(invalidScoreArb(source), Arb.int(0..100_000)) { s, v -> RatingEntry(score = s, voteCount = v) },
    )

    // 单个键槽：随机决定是否纳入该源；返回 null 表示该键不出现在输入中（键集合本身也随机）。
    fun keySlotArb(source: SourceId): Arb<Pair<SourceId, RatingEntry?>?> =
        Arb.bind(Arb.boolean(), entryArb(source)) { include, entry ->
            if (include) source to entry else null
        }

    // 任意多源输入：键集合为 5 个内容源的随机子集，值含缺失 / 有效 / 无效混合。
    val perSourceInputArb: Arb<Map<SourceId, RatingEntry?>> = Arb.bind(
        keySlotArb(SourceId.BANGUMI),
        keySlotArb(SourceId.ANILIST),
        keySlotArb(SourceId.JIKAN),
        keySlotArb(SourceId.MAL),
        keySlotArb(SourceId.VNDB),
    ) { b, a, j, m, v ->
        listOfNotNull(b, a, j, m, v).toMap()
    }

    ": 评分聚合不伪造、缺失即标记，样本不足不下结论" {
        checkAll(PropTestConfig(iterations = 100), perSourceInputArb) { input ->
            val result = useCase(input)

            // (1) 键集合一致：既不丢键，也不新增 / 回填任何源。
            result.perSource.keys shouldBe input.keys
            result.perSource.size shouldBe input.size

            input.forEach { (source, inputEntry) ->
                val outputEntry = result.perSource[source]
                if (inputEntry == null) {
                    // 缺失源原样保留为 null，绝不被其它源数据回填。
                    outputEntry.shouldBeNull()
                } else {
                    // (2) 非空条目与输入是同一对象（===），未被替换 / 伪造。
                    outputEntry.shouldNotBeNull()
                    outputEntry shouldBeSameInstanceAs inputEntry
                }
            }

            // (3) 样本不足不下结论；样本充足时各字段落在 [0,1]。
            val validCount = input.entries.count { (source, entry) ->
                entry != null && AggregateRatingsUseCase.isValidScore(source, entry.score)
            }
            if (validCount < AggregateRatingsUseCase.MIN_VALID_SAMPLES) {
                result.consensus.shouldBeNull()
            } else {
                val consensus = result.consensus
                consensus.shouldNotBeNull()
                consensus.stability shouldBeGreaterThanOrEqualTo 0f
                consensus.stability shouldBeLessThanOrEqualTo 1f
                consensus.controversy shouldBeGreaterThanOrEqualTo 0f
                consensus.controversy shouldBeLessThanOrEqualTo 1f
                consensus.priority shouldBeGreaterThanOrEqualTo 0f
                consensus.priority shouldBeLessThanOrEqualTo 1f
            }
        }
    }
})
