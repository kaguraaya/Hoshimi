package com.acgcompass.domain.usecase

import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * [AggregateRatingsUseCase] 示例和边界单元测试。
 *
 * （评分聚合不伪造、缺失即标记）的属性测试为 ，此处仅覆盖具体示例与边界。
 */
class AggregateRatingsUseCaseTest : StringSpec({

    val useCase = AggregateRatingsUseCase()

    "缺失源（null）原样保留、不被回填，键集合一致" {
        val input = mapOf(
            SourceId.BANGUMI to RatingEntry(score = 8.0f, voteCount = 100),
            SourceId.ANILIST to null,
            SourceId.JIKAN to RatingEntry(score = 7.5f, voteCount = 50),
        )

        val result = useCase(input)

        result.perSource.keys shouldBe input.keys
        result.perSource[SourceId.ANILIST].shouldBeNull()
        result.perSource[SourceId.BANGUMI] shouldBe RatingEntry(8.0f, 100)
    }

    "有效样本不足（仅 1 个）时 consensus 为 null，不下结论" {
        val input = mapOf(
            SourceId.BANGUMI to RatingEntry(score = 9.0f, voteCount = 10),
            SourceId.ANILIST to null,
        )

        useCase(input).consensus.shouldBeNull()
    }

    "无效分值（<=0 / 越界）不计入样本，导致样本不足时 consensus 为 null" {
        val input = mapOf(
            // 0 = 暂无有效评分
            SourceId.BANGUMI to RatingEntry(score = 0f, voteCount = 0),
            SourceId.JIKAN to RatingEntry(score = 8.0f, voteCount = 30),
        )

        useCase(input).consensus.shouldBeNull()
    }

    "两个一致的高分：高稳定度、低争议度、合理优先级" {
        val input = mapOf(
            // 10 分制
            SourceId.BANGUMI to RatingEntry(score = 8.6f, voteCount = 200),
            // 100 分制 -> 8.5
            SourceId.ANILIST to RatingEntry(score = 85f, voteCount = 5000),
        )

        val consensus = useCase(input).consensus
        consensus.shouldNotBeNull()
        // 三因子稳定度权重为一致性 0.40、评分人数 0.35、来源数 0.25；双源理论上限约 0.875。
        // 本例两源高度一致（极差 0.1）、票数充足 → 约 0.795，属「较高稳定度」。
        consensus.stability shouldBeGreaterThan 0.75f
        consensus.controversy shouldBeLessThan 0.1f
        consensus.priority shouldBeGreaterThan 0.5f
    }

    "分歧较大（极差 >= 2.0）：争议度不低于 0.5、稳定度不高于 0.5" {
        val input = mapOf(
            SourceId.BANGUMI to RatingEntry(score = 9.0f, voteCount = 100),
            // 极差 3.0 >= 2.0
            SourceId.JIKAN to RatingEntry(score = 6.0f, voteCount = 100),
        )

        val consensus = useCase(input).consensus
        consensus.shouldNotBeNull()
        consensus.controversy shouldBeGreaterThan 0.49f
        consensus.stability shouldBeLessThan 0.51f
    }

    "归一化：100 分制源除以 10、10 分制源原样，并裁剪到 [0,10]" {
        AggregateRatingsUseCase.normalizeToTen(SourceId.ANILIST, 85f) shouldBe 8.5f
        AggregateRatingsUseCase.normalizeToTen(SourceId.VNDB, 100f) shouldBe 10.0f
        AggregateRatingsUseCase.normalizeToTen(SourceId.BANGUMI, 7.3f) shouldBe 7.3f
        AggregateRatingsUseCase.normalizeToTen(SourceId.MAL, 999f) shouldBe 10.0f
    }

    "有效分值判定：0/负数/NaN/越界 无效，量纲内正值有效" {
        AggregateRatingsUseCase.isValidScore(SourceId.BANGUMI, 0f) shouldBe false
        AggregateRatingsUseCase.isValidScore(SourceId.BANGUMI, -1f) shouldBe false
        AggregateRatingsUseCase.isValidScore(SourceId.BANGUMI, Float.NaN) shouldBe false
        AggregateRatingsUseCase.isValidScore(SourceId.BANGUMI, 11f) shouldBe false
        AggregateRatingsUseCase.isValidScore(SourceId.ANILIST, 88f) shouldBe true
        AggregateRatingsUseCase.isValidScore(SourceId.JIKAN, 8.2f) shouldBe true
    }

    "空输入：perSource 为空且 consensus 为 null" {
        val result = useCase(emptyMap())
        result.perSource.size shouldBe 0
        result.consensus.shouldBeNull()
    }
})
