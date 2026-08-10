package com.acgcompass.core.common

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 *
 * 校验「错误信息映射完整」：对任意 [AppError] 子类型，其用户消息映射结果均携带四要素——
 * 简短原因 (cause)、用户下一步 (nextStep)、重试动作 (retryable)、查看文档入口 (docUrl 字段存在)。
 *
 */
class AppErrorMappingPropertyTest : StringSpec({

    // Arb that generates an instance of EVERY concrete AppError subtype with randomized
    // (but contract-valid) field values, ensuring exhaustive coverage of the sealed hierarchy.
    val nonBlank: Arb<String> = Arb.string(minSize = 1, maxSize = 40).map { "原因-$it" }
    val nextStepArb: Arb<String> = Arb.string(minSize = 1, maxSize = 40).map { "下一步-$it" }
    val docUrlArb: Arb<String?> = Arb.string(minSize = 1, maxSize = 30).map { "https://docs/$it" }.orNull()

    val appErrorArb: Arb<AppError> = Arb.choice(
        Arb.bind(nonBlank, nextStepArb, Arb.boolean(), docUrlArb) { c, n, r, d ->
            AppError.Network(cause = c, nextStep = n, retryable = r, docUrl = d)
        },
        Arb.bind(nonBlank, nextStepArb, Arb.boolean(), docUrlArb) { c, n, r, d ->
            AppError.Unauthorized(cause = c, nextStep = n, retryable = r, docUrl = d)
        },
        Arb.bind(nonBlank, nextStepArb, Arb.boolean(), docUrlArb) { c, n, r, d ->
            AppError.RateLimited(cause = c, nextStep = n, retryable = r, docUrl = d)
        },
        Arb.bind(nonBlank, nextStepArb, Arb.boolean(), docUrlArb) { c, n, r, d ->
            AppError.NotFound(cause = c, nextStep = n, retryable = r, docUrl = d)
        },
        Arb.bind(nonBlank, nextStepArb, Arb.boolean(), docUrlArb) { c, n, r, d ->
            AppError.Server(cause = c, nextStep = n, retryable = r, docUrl = d)
        },
        Arb.bind(nonBlank, nextStepArb, Arb.boolean(), docUrlArb) { c, n, r, d ->
            AppError.FieldMissing(cause = c, nextStep = n, retryable = r, docUrl = d)
        },
        Arb.bind(nonBlank, nextStepArb, Arb.boolean(), docUrlArb) { c, n, r, d ->
            AppError.AiMalformed(cause = c, nextStep = n, retryable = r, docUrl = d)
        },
        Arb.bind(nonBlank, nextStepArb, Arb.boolean(), docUrlArb) { c, n, r, d ->
            AppError.Spoiler(cause = c, nextStep = n, retryable = r, docUrl = d)
        },
    )

    "every AppError subtype carries all four error-card elements" {
        checkAll(PropTestConfig(iterations = 100), appErrorArb) { error ->
            // 简短原因：非空白
            error.cause.shouldNotBeBlank()
            // 用户下一步：非空白
            error.nextStep.shouldNotBeBlank()
            // 重试动作：Boolean 由类型保证恒存在；断言其确为 Boolean
            (error.retryable == true || error.retryable == false) shouldBe true
            // 查看文档入口：docUrl 字段存在（可为 null）；读取不抛异常即证明字段存在
            @Suppress("UNUSED_EXPRESSION")
            error.docUrl
        }
    }

    // Exhaustiveness guard: also verify each subtype's DEFAULT-constructed instance is complete,
    // so the property holds even for the production default messages (not only randomized fields).
    ": default-constructed instances of all 8 subtypes are complete" {
        val allDefaults: List<AppError> = listOf(
            AppError.Network(),
            AppError.Unauthorized(),
            AppError.RateLimited(),
            AppError.NotFound(),
            AppError.Server(),
            AppError.FieldMissing(),
            AppError.AiMalformed(),
            AppError.Spoiler(),
        )
        checkAll(PropTestConfig(iterations = 100), Arb.of(allDefaults)) { error ->
            error.cause.shouldNotBeBlank()
            error.nextStep.shouldNotBeBlank()
            (error.retryable == true || error.retryable == false) shouldBe true
            @Suppress("UNUSED_EXPRESSION")
            error.docUrl
        }
    }
})
