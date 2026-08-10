package com.acgcompass.core.network

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * 验证 [DefaultDataSourceOrchestrator] 的核心降级行为。
 *
 * 降级顺序确定性的全称属性测试见 ，此处仅覆盖具体示例与边界。
 */
class DataSourceOrchestratorTest : StringSpec({

    val orchestrator = DefaultDataSourceOrchestrator()

    fun success(value: String, confidence: Float = 1f, missing: Set<String> = emptySet()) =
        SourceFetcher { AppResult.Success(SourceData(value, confidence, missing)) }

    val failing = SourceFetcher<String> { AppResult.Failure(AppError.Server()) }

    "固定回退顺序为 Bangumi → AniList → Jikan → MAL → VNDB" {
        DataSourceOrchestrator.FALLBACK_ORDER shouldBe listOf(
            SourceId.BANGUMI,
            SourceId.ANILIST,
            SourceId.JIKAN,
            SourceId.MAL,
            SourceId.VNDB,
        )
    }

    "命中首个可用源并附 sourceTag 与置信度" {
        runTest {
            val request = SourceRequest.of(
                SourceId.BANGUMI to success("bgm", confidence = 0.9f),
                SourceId.ANILIST to success("ani"),
            )
            val outcome = orchestrator.fetch(request)
            outcome.shouldBeInstanceOf<SourceOutcome.Success<String>>()
            outcome.data shouldBe "bgm"
            outcome.sourceTag shouldBe SourceId.BANGUMI
            outcome.confidence shouldBe 0.9f
        }
    }

    "跳过未登记 / 运行时不可用的源，降级到下一个可用源" {
        runTest {
            val request = SourceRequest.of(
                // Bangumi 未登记（不参与），AniList 运行时不可用（fetcher 返回 null），Jikan 命中。
                SourceId.ANILIST to SourceFetcher<String> { null },
                SourceId.JIKAN to success("jikan"),
            )
            val outcome = orchestrator.fetch(request)
            outcome.shouldBeInstanceOf<SourceOutcome.Success<String>>()
            outcome.sourceTag shouldBe SourceId.JIKAN
        }
    }

    "某源调用失败时降级到后续源" {
        runTest {
            val request = SourceRequest.of(
                SourceId.BANGUMI to failing,
                SourceId.ANILIST to success("ani"),
            )
            val outcome = orchestrator.fetch(request)
            outcome.shouldBeInstanceOf<SourceOutcome.Success<String>>()
            outcome.sourceTag shouldBe SourceId.ANILIST
        }
    }

    "字段级缺失在 Success.missingFields 中标记（暂无数据）" {
        runTest {
            val request = SourceRequest.of(
                SourceId.BANGUMI to success("bgm", missing = setOf("rank", "coverUrl")),
            )
            val outcome = orchestrator.fetch(request)
            outcome.shouldBeInstanceOf<SourceOutcome.Success<String>>()
            outcome.missingFields shouldBe setOf("rank", "coverUrl")
        }
    }

    "全部源调用失败时返回可重试错误，保留最近一次失败原因" {
        runTest {
            val request = SourceRequest.of(
                SourceId.BANGUMI to failing,
                SourceId.VNDB to SourceFetcher<String> { AppResult.Failure(AppError.RateLimited()) },
            )
            val outcome = orchestrator.fetch(request)
            outcome.shouldBeInstanceOf<SourceOutcome.Failure>()
            outcome.error.shouldBeInstanceOf<AppError.RateLimited>()
            outcome.error.retryable shouldBe true
        }
    }

    "全部源不参与时返回可重试的 FieldMissing（暂无数据）" {
        runTest {
            val request = SourceRequest<String>(emptyMap())
            val outcome = orchestrator.fetch(request)
            outcome.shouldBeInstanceOf<SourceOutcome.Failure>()
            outcome.error.shouldBeInstanceOf<AppError.FieldMissing>()
            outcome.error.retryable shouldBe true
        }
    }
})
