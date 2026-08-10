package com.acgcompass.domain.matching

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.SourceRef
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.float
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 *
 * 阈值规则：[decideMatch] 在最高置信度候选 ≥ [MATCH_THRESHOLD] 时自动合并，
 * 否则标记为需用户确认；返回的 candidates 始终按置信度降序。
 *
 * 手动纠正持久性：[resolveLink] 对任意重算值，都会原样保留 `userOverridden == true` 的链接。
 */
class MatchThresholdOverridePropertyTest : StringSpec({

    @OptIn(ExperimentalKotest::class)
    val config = PropTestConfig(iterations = 100)

    /** 生成一个置信度在 [0,1] 的候选 [WorkMatch]；其余字段对本属性无关，给定确定性占位值。 */
    val workMatchArb: Arb<WorkMatch> =
        Arb.bind(
            Arb.float(0.0f, 1.0f),
            Arb.enum<SourceId>(),
            Arb.string(0, 8),
        ) { confidence, source, idSuffix ->
            WorkMatch(
                work = Work(
                    id = "w-$idSuffix",
                    titles = Titles(canonical = "title-$idSuffix"),
                    mediaType = MediaType.ANIME,
                    primarySource = source,
                ),
                matchConfidence = confidence,
                sourceTag = source,
            )
        }

    val sourceRefArb: Arb<SourceRef> =
        Arb.bind(
            Arb.enum<SourceId>(),
            Arb.string(0, 8),
            Arb.float(0.0f, 1.0f),
            Arb.boolean(),
        ) { source, itemId, confidence, overridden ->
            SourceRef(
                sourceId = source,
                sourceItemId = itemId,
                matchConfidence = confidence,
                userOverridden = overridden,
            )
        }

    "decideMatch auto-merges iff max confidence reaches the threshold" {
        checkAll(config, Arb.list(workMatchArb, 0..8)) { candidates ->
            val decision = decideMatch(candidates)

            // candidates always sorted descending by confidence.
            val confidences = decision.candidates.map { it.matchConfidence }
            confidences shouldBe confidences.sortedDescending()

            val maxConfidence = candidates.maxOfOrNull { it.matchConfidence }
            if (maxConfidence != null && maxConfidence >= MATCH_THRESHOLD) {
                val autoMerge = decision.shouldBeInstanceOf<MatchDecision.AutoMerge>()
                autoMerge.best.matchConfidence shouldBe maxConfidence
                autoMerge.best shouldBe autoMerge.candidates.first()
            } else {
                decision.shouldBeInstanceOf<MatchDecision.NeedsConfirmation>()
            }
        }
    }

    "a user-overridden link is preserved for every recomputed value" {
        checkAll(config, sourceRefArb, sourceRefArb) { currentRef, recomputedRef ->
            val current = ResolvedLink(sourceRef = currentRef, userOverridden = true)
            val recomputed = ResolvedLink(sourceRef = recomputedRef, userOverridden = false)

            // userOverridden=true current link must survive any recompute.
            resolveLink(current, recomputed) shouldBe current

            // Sanity: a non-overridden current link adopts the recomputed value.
            val nonOverridden = current.copy(userOverridden = false)
            (resolveLink(nonOverridden, recomputed) == recomputed).shouldBeTrue()
        }
    }
})
