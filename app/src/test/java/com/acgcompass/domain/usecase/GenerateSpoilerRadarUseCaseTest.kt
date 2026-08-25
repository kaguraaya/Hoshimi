package com.acgcompass.domain.usecase

import com.acgcompass.core.common.AppError
import com.acgcompass.domain.ai.AiEngine
import com.acgcompass.domain.ai.AiRunOptions
import com.acgcompass.domain.ai.AiRunResult
import com.acgcompass.domain.ai.AiTask
import com.acgcompass.domain.ai.CostRange
import com.acgcompass.domain.ai.RadarSummarySource
import com.acgcompass.domain.ai.SpoilerLevel
import com.acgcompass.domain.ai.SpoilerRadarOutput
import com.acgcompass.domain.ai.SpoilerScrubber
import com.acgcompass.domain.model.AiGenerator
import com.acgcompass.domain.model.AiResult
import com.acgcompass.domain.model.AiTaskType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * 。
 *
 * 覆盖：AI 成功 → generator=AI 且来源含「AI 总结」；未配置 / 失败 / 低置信 → 规则回退 generator=RULE；
 * 预留剧透等级被强制收敛到无剧透；成本未确认透传；来源标注按非空信号推导。
 */
class GenerateSpoilerRadarUseCaseTest : StringSpec({

    fun useCase(engine: AiEngine, now: Long = 1_000L): GenerateSpoilerRadarUseCase = GenerateSpoilerRadarUseCase(
        aiEngine = engine,
        scrubber = SpoilerScrubber.NONE,
        clock = { now },
    )

    val richRequest = RadarRequest(
        workId = "w1",
        title = "测试作品",
        tags = listOf("热血", "作画"),
        userComments = listOf("音乐很赞"),
        publicReviews = listOf("节奏稍慢"),
    )

    "AI 成功：generator=AI，来源标注含 AI 总结及全部输入来源" {
        runTest {
            val payload = SpoilerRadarOutput(
                overallImpression = "整体偏热血",
                pros = listOf("作画好"),
                confidence = 0.8f,
                sources = listOf("模型自带来源"),
            )
            val engine = FakeAiEngine(
                AiRunResult.Success(
                    payload = payload,
                    result = aiResult(generator = AiGenerator.AI, confidence = 0.8f, generatedAt = 555L),
                ),
            )

            val outcome = useCase(engine).invoke(richRequest)

            outcome.shouldBeInstanceOf<RadarOutcome.Ready>()
            val result = outcome.result
            result.generator shouldBe AiGenerator.AI
            result.generatedAt shouldBe 555L
            result.summarySources shouldContainExactly listOf(
                RadarSummarySource.USER_COMMENTS,
                RadarSummarySource.PUBLIC_REVIEWS,
                RadarSummarySource.TAG_STATS,
                RadarSummarySource.AI,
            )
            // 展示用 sources 以结构化标注覆盖，口径统一。
            result.output.sources shouldContainExactly listOf(
                "用户短评", "公共 Reviews", "标签统计", "AI 总结",
            )
        }
    }

    "未配置 AI key：回退到本地规则版，generator=RULE 且来源不含 AI" {
        runTest {
            val engine = FakeAiEngine(AiRunResult.NotConfigured)

            val outcome = useCase(engine, now = 4_242L).invoke(richRequest)

            outcome.shouldBeInstanceOf<RadarOutcome.Ready>()
            val result = outcome.result
            result.generator shouldBe AiGenerator.RULE
            result.generatedAt shouldBe 4_242L
            result.summarySources shouldNotContain RadarSummarySource.AI
            // 规则引擎产出完整维度，不编造。
            result.output.overallImpression.isNotEmpty() shouldBe true
        }
    }

    "AI 失败：同样回退到规则版，保证雷达始终可展示" {
        runTest {
            val engine = FakeAiEngine(AiRunResult.Failure(AppError.Server()))

            val outcome = useCase(engine).invoke(richRequest)

            outcome.shouldBeInstanceOf<RadarOutcome.Ready>()
            outcome.result.generator shouldBe AiGenerator.RULE
        }
    }

    "AI 低置信：回退到规则版而非展示编造内容" {
        runTest {
            val engine = FakeAiEngine(
                AiRunResult.LowConfidence(
                    result = aiResult(generator = AiGenerator.AI, confidence = 0f, generatedAt = 9L),
                    error = AppError.AiMalformed(),
                ),
            )

            val outcome = useCase(engine).invoke(richRequest)

            outcome.shouldBeInstanceOf<RadarOutcome.Ready>()
            outcome.result.generator shouldBe AiGenerator.RULE
        }
    }

    "成本未确认：透传 NeedsCostConfirmation，不擅自生成" {
        runTest {
            val estimate = CostRange(minTokens = 100, maxTokens = 500, summaryOnlyAvailable = true)
            val engine = FakeAiEngine(AiRunResult.NeedsConfirmation(estimate))

            val outcome = useCase(engine).invoke(richRequest, AiRunOptions(confirmed = false))

            outcome.shouldBeInstanceOf<RadarOutcome.NeedsCostConfirmation>()
            outcome.estimate shouldBe estimate
        }
    }

    "预留剧透等级被强制收敛到无剧透" {
        runTest {
            val engine = FakeAiEngine(AiRunResult.NotConfigured)

            val mild = useCase(engine).invoke(richRequest.copy(spoilerLevel = SpoilerLevel.MILD))
            val full = useCase(engine).invoke(richRequest.copy(spoilerLevel = SpoilerLevel.FULL))

            mild.shouldBeInstanceOf<RadarOutcome.Ready>()
            mild.result.requestedLevel shouldBe SpoilerLevel.MILD
            mild.result.effectiveLevel shouldBe SpoilerLevel.NO_SPOILER

            full.shouldBeInstanceOf<RadarOutcome.Ready>()
            full.result.effectiveLevel shouldBe SpoilerLevel.NO_SPOILER
        }
    }

    "无剧透等级原样生效" {
        runTest {
            val engine = FakeAiEngine(AiRunResult.NotConfigured)

            val outcome = useCase(engine).invoke(richRequest.copy(spoilerLevel = SpoilerLevel.NO_SPOILER))

            outcome.shouldBeInstanceOf<RadarOutcome.Ready>()
            outcome.result.effectiveLevel shouldBe SpoilerLevel.NO_SPOILER
        }
    }

    "来源标注仅反映非空信号：仅标签时不含短评 / Reviews" {
        runTest {
            val engine = FakeAiEngine(AiRunResult.NotConfigured)
            val tagsOnly = RadarRequest(workId = "w2", tags = listOf("治愈"))

            val outcome = useCase(engine).invoke(tagsOnly)

            outcome.shouldBeInstanceOf<RadarOutcome.Ready>()
            outcome.result.summarySources shouldContainExactly listOf(RadarSummarySource.TAG_STATS)
        }
    }

    "关闭「允许 AI 分析评论」：发给 AI 的内容里不含任何评论文本，标签照常发送" {
        runTest {
            val payload = SpoilerRadarOutput(overallImpression = "整体偏热血", confidence = 0.8f)
            fun engine() = FakeAiEngine(
                AiRunResult.Success(
                    payload = payload,
                    result = aiResult(generator = AiGenerator.AI, confidence = 0.8f, generatedAt = 555L),
                ),
            )
            val allowed = engine()
            val denied = engine()

            useCase(allowed).invoke(richRequest)
            useCase(denied).invoke(richRequest.copy(allowReviewsToAi = false))

            val sentWhenAllowed = requireNotNull(allowed.lastUserContent)
            sentWhenAllowed.contains("音乐很赞") shouldBe true
            sentWhenAllowed.contains("节奏稍慢") shouldBe true
            val sentWhenDenied = requireNotNull(denied.lastUserContent)
            sentWhenDenied.contains("音乐很赞") shouldBe false
            sentWhenDenied.contains("节奏稍慢") shouldBe false
            sentWhenDenied.contains("热血") shouldBe true
        }
    }
})

private fun aiResult(generator: AiGenerator, confidence: Float, generatedAt: Long): AiResult = AiResult(
    id = "r1",
    workId = "w1",
    taskType = AiTaskType.SPOILER_RADAR,
    generator = generator,
    payloadJson = "{}",
    confidence = confidence,
    dataSources = emptyList(),
    generatedAt = generatedAt,
)

/** 固定返回预设结果的 [AiEngine] 测试替身。 */
private class FakeAiEngine(private val result: AiRunResult<*>) : AiEngine {
    var lastUserContent: String? = null
        private set

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T> run(task: AiTask<T>, options: AiRunOptions): AiRunResult<T> {
        lastUserContent = task.userContent
        return result as AiRunResult<T>
    }

    override fun estimateCost(task: AiTask<*>, options: AiRunOptions): CostRange =
        CostRange(minTokens = 0, maxTokens = 0, summaryOnlyAvailable = false)
}
