package com.acgcompass.data.credential

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain

/**
 * 凭据脱敏与 [SecretBundle] 脱敏视图单元测试。
 *
 * 覆盖掩码核心规则：最多保留末尾 4 字符、短值不泄露、敏感字段被掩码、非敏感字段（baseUrl/model）保留。
 * 注：[CredentialStoreImpl] 依赖 Android Keystore / Context，其端到端行为由插桩测试覆盖。
 */
class CredentialMaskingTest : StringSpec({

    "null 输入返回 null" {
        CredentialMasking.mask(null) shouldBe null
    }

    "空白输入原样返回（无敏感信息可泄露）" {
        CredentialMasking.mask("") shouldBe ""
        CredentialMasking.mask("   ") shouldBe "   "
    }

    "长度 ≤4 的短值不暴露任何字符" {
        CredentialMasking.mask("a") shouldBe "****…"
        CredentialMasking.mask("abcd") shouldBe "****…"
    }

    "长值仅暴露末尾 ≤4 字符" {
        // 典型 OpenAI 风格 key：末尾保留 4 字符。
        CredentialMasking.mask("sk-1234567890abcdwxyz") shouldBe "****…wxyz"
        // 长度 6：末尾保留 min(4, 6-4)=2 字符。
        CredentialMasking.mask("abcdef") shouldBe "****…ef"
    }

    "掩码绝不暴露超过末尾 4 个字符" {
        val raw = "supersecrettoken1234"
        val masked = CredentialMasking.mask(raw)!!
        masked shouldEndWith "1234"
        masked shouldNotContain "supersecrettoken"
    }

    "SecretBundle.toRedacted 掩码敏感字段并保留非敏感字段" {
        val bundle = SecretBundle(
            token = "bgm-tokenvalue1234",
            apiKey = "sk-apikeyvalue5678",
            clientId = "client-id-abcd",
            clientSecret = "client-secret-efgh",
            baseUrl = "https://api.example.com/v1",
            model = "gpt-4o-mini",
        )

        val redacted = bundle.toRedacted()

        redacted.token shouldBe "****…1234"
        redacted.apiKey shouldBe "****…5678"
        redacted.clientId shouldBe "****…abcd"
        redacted.clientSecret shouldBe "****…efgh"
        // 非敏感配置原样保留，便于用户核对。
        redacted.baseUrl shouldBe "https://api.example.com/v1"
        redacted.model shouldBe "gpt-4o-mini"
    }

    "脱敏视图不含任何原始敏感明文" {
        val bundle = SecretBundle(token = "verysecrettokenABCDEFG")
        val redacted = bundle.toRedacted()
        redacted.token!! shouldNotContain "verysecrettoken"
    }

    "hasAnyValue 在全空时为 false，含任意值时为 true" {
        SecretBundle().hasAnyValue() shouldBe false
        SecretBundle(token = "  ").hasAnyValue() shouldBe false
        SecretBundle(apiKey = "x").hasAnyValue() shouldBe true
    }
})
