package com.acgcompass.domain.matching

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 示例 / 边界单元测试，覆盖标题归一化、相似度、综合置信度与匹配决策。
 * 幂等性与阈值另有属性测试覆盖。
 */
class MatchingTest : StringSpec({

    // --- normalizeTitle  ---

    "normalizeTitle folds case, full-width and punctuation" {
        // 全角字母 + 标点 + 大小写混合 → 折叠后只剩小写字母 token，单空格分隔。
        normalizeTitle("Ｆate/Stay  Night!") shouldBe "fate stay night"
    }

    "normalizeTitle trims and collapses whitespace" {
        normalizeTitle("   Steins;Gate   ") shouldBe "steins gate"
    }

    "normalizeTitle on empty string is empty" {
        normalizeTitle("") shouldBe ""
    }

    "normalizeTitle is idempotent on examples" {
        val samples = listOf(
            "Ｆate/Stay Night!",
            "命运石之门　０",
            "Re:ゼロから始める異世界生活",
            "  THE [email protected]  ",
        )
        samples.forEach { raw ->
            val once = normalizeTitle(raw)
            normalizeTitle(once) shouldBe once
        }
    }

    "normalizeTitle maps full-width digits and katakana via NFKC" {
        // 全角数字 ０ → 半角 0，半角片假名 ｶ → 全角 カ（NFKC 规范）。
        normalizeTitle("ROBOTICS；ＮＯＴＥＳ ０") shouldBe "robotics notes 0"
    }

    // --- similarity  ---

    "identical titles after normalization have similarity 1.0" {
        similarity("Fate/Stay Night", "fate stay night") shouldBe 1.0
    }

    "two empty titles are similar" {
        similarity("", "") shouldBe 1.0
    }

    "completely different titles have low similarity" {
        similarity("Clannad", "Bakemonogatari") shouldBeLessThan 0.4
    }

    "minor typo keeps high similarity" {
        similarity("Steins;Gate", "Steins Gata") shouldBeGreaterThan 0.7
    }

    "similarity is always within [0,1]" {
        val s = similarity("ABCDEF", "XYZ")
        s shouldBeGreaterThanOrEqual 0.0
        s shouldBe minOf(s, 1.0)
    }

    // --- matchConfidence ( inputs) ---

    "matchConfidence equals title similarity when no year/type signals" {
        val expected = similarity("Clannad", "Clannad After Story")
        matchConfidence("Clannad", "Clannad After Story") shouldBe expected
    }

    "matching year and type boost confidence above title-only" {
        val titleOnly = matchConfidence("Clannad", "Clannad After Story")
        val withSignals = matchConfidence(
            candidate = "Clannad",
            query = "Clannad After Story",
            yearMatch = true,
            typeMatch = true,
        )
        withSignals shouldBeGreaterThan titleOnly
    }

    "mismatching year and type lower confidence below title-only" {
        val titleOnly = matchConfidence("Clannad", "Clannad")
        val withMismatch = matchConfidence(
            candidate = "Clannad",
            query = "Clannad",
            yearMatch = false,
            typeMatch = false,
        )
        withMismatch shouldBeLessThan titleOnly
    }

    "matchConfidence stays within [0,1]" {
        val c = matchConfidence("totally different", "xyz", yearMatch = false, typeMatch = false)
        c shouldBeGreaterThanOrEqual 0.0
        (c <= 1.0) shouldBe true
    }

    // --- decideMatch / MatchDecision  ---

    "high-confidence candidate auto-merges to the best" {
        val low = workMatch("a", 0.40f)
        val high = workMatch("b", 0.92f)
        val decision = decideMatch(listOf(low, high))
        decision.shouldBeInstanceOf<MatchDecision.AutoMerge>()
        decision.best shouldBe high
        // candidates are ranked by confidence descending.
        decision.candidates shouldBe listOf(high, low)
    }

    "candidate exactly at threshold auto-merges" {
        val decision = decideMatch(listOf(workMatch("a", MATCH_THRESHOLD.toFloat())))
        decision.shouldBeInstanceOf<MatchDecision.AutoMerge>()
    }

    "all low-confidence candidates need confirmation" {
        val decision = decideMatch(listOf(workMatch("a", 0.50f), workMatch("b", 0.70f)))
        decision.shouldBeInstanceOf<MatchDecision.NeedsConfirmation>()
    }

    "empty candidate list needs confirmation" {
        val decision = decideMatch(emptyList())
        decision.shouldBeInstanceOf<MatchDecision.NeedsConfirmation>()
        decision.candidates shouldBe emptyList()
    }
})

private fun workMatch(id: String, confidence: Float): WorkMatch = WorkMatch(
    work = Work(
        id = id,
        titles = Titles(canonical = id),
        mediaType = MediaType.ANIME,
        primarySource = SourceId.BANGUMI,
    ),
    matchConfidence = confidence,
    sourceTag = SourceId.BANGUMI,
)
