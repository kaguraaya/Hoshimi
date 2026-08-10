package com.acgcompass.domain.funfeature

import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.TasteProfile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * 趣味功能领域纯函数单元测试。
 *
 * 覆盖：安利债务、吃灰博物馆、补番人格、
 * 补番遗书、情绪风险提示。
 *
 */
class FunFeaturesTest : StringSpec({

    fun backlog(
        id: String,
        dustDays: Int = 0,
        inDustMuseum: Boolean = false,
        moodTags: List<String> = emptyList(),
        riskTags: List<String> = emptyList(),
    ) = BacklogItem(
        workId = id,
        addedAt = 0L,
        dustDays = dustDays,
        inDustMuseum = inDustMuseum,
        moodTags = moodTags,
        riskTags = riskTags,
    )

    // ---- 安利债务 -------------------------------------------------

    "owed count = backlog items recommended >= threshold" {
        val items = listOf(backlog("a"), backlog("b"), backlog("c"))
        val counts = mapOf("a" to 2, "b" to 0) // c missing -> 0
        val debt = computeRecommendationDebt(items, counts)
        debt.owedCount shouldBe 1
        debt.message shouldContain "你欠群友 1 部番没看"
    }

    "zero owed gives clean message, never fabricates" {
        val debt = computeRecommendationDebt(
            backlog = listOf(backlog("a"), backlog("b")),
            recommendedCounts = mapOf("a" to 0, "b" to 0),
        )
        debt.owedCount shouldBe 0
        debt.message shouldContain "清白"
    }

    "custom minRecommended threshold raises the bar" {
        val items = listOf(backlog("a"), backlog("b"))
        val counts = mapOf("a" to 1, "b" to 3)
        computeRecommendationDebt(items, counts, minRecommended = 2).owedCount shouldBe 1
    }

    // ---- 吃灰博物馆 ----------------------------------------------

    "selects items over threshold or explicitly flagged, sorted by dust desc" {
        val items = listOf(
            backlog("fresh", dustDays = 5),
            backlog("old", dustDays = 45),
            backlog("flagged", dustDays = 1, inDustMuseum = true),
            backlog("ancient", dustDays = 90),
        )
        val museum = selectDustMuseum(items, thresholdDays = 30)
        museum.map { it.workId } shouldContainExactly listOf("ancient", "old", "flagged")
    }

    "empty backlog yields empty museum" {
        selectDustMuseum(emptyList()).shouldBeEmpty()
    }

    // ---- 补番人格 -------------------------------------------------

    "reuses an existing taste profile title when present" {
        val profile = TasteProfile(
            id = "p",
            strictness = 0.9f,
            avgScore = 8.5f,
            highScoreRarity = 0.8f,
            confidence = 0.9f,
            generatedAt = 0L,
            titles = listOf("番剧考古学家"),
        )
        bingePersona(profile) shouldBe "番剧考古学家"
    }

    "falls back to signal-based persona when no titles, always non-empty" {
        bingePersona(TasteSignals(avgScore = 6f, strictness = 0.8f, highScoreRarity = 0.2f)) shouldBe
            "嘴上嫌弃身体诚实的严苛评审"
        bingePersona(TasteSignals(avgScore = 9f, strictness = 0.1f, highScoreRarity = 0.1f)) shouldBe
            "雨露均沾的高分博爱党"
        bingePersona(TasteSignals(avgScore = 6.5f, strictness = 0.1f, highScoreRarity = 0.1f)) shouldBe
            "随缘补番的佛系观众"
    }

    // ---- 补番遗书 -------------------------------------------------

    "declaration references pending count and flagship when provided" {
        val will = BingeWill.declaration(pendingCount = 7, flagshipTitle = "命运石之门")
        will shouldContain "7 部番"
        will shouldContain "《命运石之门》"
    }

    "declaration is non-empty and safe with zero / blank inputs" {
        val will = BingeWill.declaration(pendingCount = -3, flagshipTitle = "  ")
        will shouldContain "此生再无未补之番"
    }

    // ---- 情绪风险提示 --------------------------------------------

    "maps risk tags to warnings, deduped and declaration-ordered" {
        val warnings = assessMoodRisks(listOf("致郁", "党争", "致郁", "高上头"))
        warnings.map { it.risk } shouldContainExactly listOf(
            MoodRisk.DEPRESSING,
            MoodRisk.FACTION_WAR,
            MoodRisk.HIGHLY_ADDICTIVE,
        )
        warnings.first().warning shouldContain "致郁"
    }

    "no matching tags yields no warnings (never fabricates)" {
        assessMoodRisks(listOf("治愈", "日常")).shouldBeEmpty()
    }

    "backlog item overload merges mood and risk tags" {
        val item = backlog("a", moodTags = listOf("压抑"), riskTags = listOf("上头"))
        assessMoodRisks(item).map { it.risk } shouldContainExactly listOf(
            MoodRisk.OPPRESSIVE,
            MoodRisk.HIGHLY_ADDICTIVE,
        )
    }
})
