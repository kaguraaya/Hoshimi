package com.acgcompass.core.network

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.model.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.enum
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest

/**
 * 单个源在一次请求中可能处于的四种「可用性」状态（供  的可用性组合生成器使用）：
 * - [SUCCESS]：已登记取数器且取数成功（唯一会被命中的状态）。
 * - [FAILURE]：已登记取数器但本次调用失败（可重试错误）——应被跳过、降级。
 * - [UNCONFIGURED]：已登记但运行时不可用（取数器返回 `null`，如未配置凭据）——应被跳过。
 * - [NOT_REGISTERED]：未在请求中登记取数器（不参与本次请求）——应被跳过。
 */
private enum class SourceState { SUCCESS, FAILURE, UNCONFIGURED, NOT_REGISTERED }

/**
 * 全称属性测试：[DefaultDataSourceOrchestrator] 的「降级顺序确定性」（设计 ）。
 *
 * 对**任意**的源可用性组合，编排器只依据固定回退顺序 [DataSourceOrchestrator.FALLBACK_ORDER]
 * （`Bangumi → AniList → Jikan → MAL → VNDB`）选源：返回值等于该顺序中**第一个**真正可用
 * （已登记 + 已配置 + 取数成功）的源；若不存在这样的源，则返回**可重试**的「暂无数据」失败结果
 * 且**绝不抛出**。
 *
 * 示例级用例见 [DataSourceOrchestratorTest]；本文件只承载  的全称断言。
 */
class DataSourceOrchestratorPropertyTest : StringSpec({

    val orchestrator = DefaultDataSourceOrchestrator()

    val stateArb: Arb<SourceState> = Arb.enum<SourceState>()

    // 为五个领域源各自独立随机一个可用性状态，构成一次请求的可用性组合。
    val availabilityArb: Arb<Map<SourceId, SourceState>> = arbitrary {
        SourceId.entries.associateWith { stateArb.bind() }
    }

    // 依据可用性状态构造对应的取数器；NOT_REGISTERED 不登记（从 map 中省略）。
    fun fetcherFor(state: SourceState): SourceFetcher<String>? = when (state) {
        SourceState.SUCCESS -> SourceFetcher { AppResult.Success(SourceData("ok")) }
        SourceState.FAILURE -> SourceFetcher { AppResult.Failure(AppError.Server()) }
        SourceState.UNCONFIGURED -> SourceFetcher { null }
        SourceState.NOT_REGISTERED -> null
    }

    ": 对任意可用性组合，命中固定回退顺序中第一个可用源；否则返回可重试失败且不抛出" {
        // 用 runTest 提供协程作用域以调用挂起的 fetch；checkAll 在其内部驱动全部迭代。
        runTest {
            checkAll(PropTestConfig(iterations = 100), availabilityArb) { availability ->
                // 仅登记非 NOT_REGISTERED 的源（其取数器各自反映 SUCCESS/FAILURE/UNCONFIGURED）。
                val fetchers: Map<SourceId, SourceFetcher<String>?> = availability
                    .filterValues { it != SourceState.NOT_REGISTERED }
                    .mapValues { (_, state) -> fetcherFor(state) }
                val request = SourceRequest(fetchers)

                // 预期：固定回退顺序中第一个状态为 SUCCESS 的源。
                val expectedSource: SourceId? = DataSourceOrchestrator.FALLBACK_ORDER
                    .firstOrNull { availability[it] == SourceState.SUCCESS }

                val outcome = orchestrator.fetch(request)

                if (expectedSource != null) {
                    outcome.shouldBeInstanceOf<SourceOutcome.Success<String>>()
                    outcome.sourceTag shouldBe expectedSource
                } else {
                    // 无任何可命中源：必须是可重试的「暂无数据」失败结果（绝不抛出、绝不臆造）。
                    outcome.shouldBeInstanceOf<SourceOutcome.Failure>()
                    outcome.error.retryable shouldBe true
                }
            }
        }
    }
})
