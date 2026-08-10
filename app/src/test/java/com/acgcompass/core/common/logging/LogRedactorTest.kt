package com.acgcompass.core.common.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * 单元测试：[LogRedactor.redact] 纯函数脱敏。
 *
 * 覆盖：sk- key、Bearer token、JWT、api_key 字段、通用长密钥被掩码；短/普通字符串保持不变。
 * 关键安全断言：输出中绝不出现完整原始凭据明文。
 */
class LogRedactorTest : StringSpec({

    "OpenAI 风格 sk- key 被掩码且不暴露完整值" {
        val secret = "sk-abcdefghijklmnopqrstuvwxyz1234"
        val out = LogRedactor.redact("calling openai with key $secret done")
        out shouldNotContain secret
        out shouldContain "****"
        // 保留末尾 4 字符用于人工核对。
        out shouldContain "1234"
    }

    "Authorization Bearer token 被掩码但保留 scheme 关键字" {
        val token = "eyXabc123def456ghi789jkl"
        val out = LogRedactor.redact("Authorization: Bearer $token")
        out shouldNotContain token
        out shouldContain "Bearer"
        out shouldContain "****"
    }

    "JWT 三段式 token 被掩码且不暴露完整值" {
        val jwt =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
                "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ." +
                "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val out = LogRedactor.redact("token=$jwt")
        out shouldNotContain jwt
        out shouldContain "****"
    }

    "api_key JSON 字段值被掩码，字段名保留" {
        val key = "supersecretapikeyvalue12345"
        val out = LogRedactor.redact("""{"api_key":"$key","model":"gpt-4o"}""")
        out shouldNotContain key
        out shouldContain "api_key"
        // 非敏感字段保留，便于排查。
        out shouldContain "gpt-4o"
    }

    "client_secret query 参数值被掩码" {
        val secret = "csecretABCDEF1234567890xyz"
        val out = LogRedactor.redact("https://api.example.com/oauth?client_secret=$secret&grant=code")
        out shouldNotContain secret
        out shouldContain "client_secret"
    }

    "通用长字母数字密钥（含字母与数字）被掩码" {
        val secret = "A1b2C3d4E5f6G7h8I9j0K1l2"
        val out = LogRedactor.redact("payload key is $secret end")
        out shouldNotContain secret
        out shouldContain "****"
    }

    "普通短字符串保持不变" {
        val msg = "user logged in successfully"
        LogRedactor.redact(msg) shouldBe msg
    }

    "普通句子（无密钥）保持不变" {
        val msg = "Fetched 12 works from Bangumi, took 340 ms"
        LogRedactor.redact(msg) shouldBe msg
    }

    "纯字母长单词（无数字）不被误判为密钥" {
        val msg = "internationalization and localization are supported"
        LogRedactor.redact(msg) shouldBe msg
    }

    "null 与空字符串安全处理" {
        LogRedactor.redact(null) shouldBe ""
        LogRedactor.redact("") shouldBe ""
    }
})
