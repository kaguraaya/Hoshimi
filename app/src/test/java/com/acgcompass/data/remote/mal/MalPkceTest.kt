package com.acgcompass.data.remote.mal

import com.acgcompass.core.network.NetworkConstants
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import java.security.SecureRandom
import okhttp3.HttpUrl.Companion.toHttpUrl

// 覆盖 code_verifier 字符集和长度、plain challenge 及授权 URL 参数拼接。
class MalPkceTest : StringSpec({

    val unreserved = Regex("^[A-Za-z0-9._~-]+$")

    "generateCodeVerifier produces RFC7636 unreserved chars within 43..128" {
        repeat(50) {
            val v = MalPkce.generateCodeVerifier()
            v.length.shouldBeInRange(MalPkce.MIN_VERIFIER_LENGTH..MalPkce.MAX_VERIFIER_LENGTH)
            v shouldMatch unreserved
        }
    }

    "generateCodeVerifier clamps requested length into the valid range" {
        MalPkce.generateCodeVerifier(length = 1).length shouldBe MalPkce.MIN_VERIFIER_LENGTH
        MalPkce.generateCodeVerifier(length = 9999).length shouldBe MalPkce.MAX_VERIFIER_LENGTH
        MalPkce.generateCodeVerifier(length = 64).length shouldBe 64
    }

    "generateCodeVerifier is deterministic for a seeded random (testability)" {
        // 注：SecureRandom(seed) 构造器在部分平台（如 Windows 原生 provider）并非确定性；
        // 使用 SHA1PRNG 并在取数前 setSeed 才能保证可复现（RFC7636 工具仅依赖注入的随机源）。
        fun seeded() = SecureRandom.getInstance("SHA1PRNG").apply { setSeed("seed".toByteArray()) }
        val a = MalPkce.generateCodeVerifier(length = 50, random = seeded())
        val b = MalPkce.generateCodeVerifier(length = 50, random = seeded())
        a shouldBe b
    }

    "codeChallenge equals verifier for plain method" {
        val v = MalPkce.generateCodeVerifier()
        MalPkce.codeChallenge(v) shouldBe v
    }

    "buildAuthorizeUrl carries required OAuth2 PKCE query params" {
        val url = MalPkce.buildAuthorizeUrl(
            clientId = "client-123",
            codeChallenge = "verifier-xyz",
            state = "state-abc",
        )
        url shouldStartWith NetworkConstants.MAL_OAUTH_BASE_URL + "authorize"

        val parsed = url.toHttpUrl()
        parsed.queryParameter("response_type") shouldBe "code"
        parsed.queryParameter("client_id") shouldBe "client-123"
        parsed.queryParameter("state") shouldBe "state-abc"
        parsed.queryParameter("code_challenge") shouldBe "verifier-xyz"
        parsed.queryParameter("code_challenge_method") shouldBe MalPkce.CODE_CHALLENGE_METHOD_PLAIN
        // 未传 redirect_uri 时不应出现该参数。
        parsed.queryParameter("redirect_uri") shouldBe null
    }

    "buildAuthorizeUrl includes redirect_uri when provided" {
        val url = MalPkce.buildAuthorizeUrl(
            clientId = "c",
            codeChallenge = "ch",
            state = "s",
            redirectUri = "acgcompass://oauth/mal",
        )
        url.toHttpUrl().queryParameter("redirect_uri") shouldBe "acgcompass://oauth/mal"
    }

    "buildAuthorizeUrl uses plain method (S256 not supported by MAL)" {
        val url = MalPkce.buildAuthorizeUrl(clientId = "c", codeChallenge = "ch", state = "s")
        url shouldContain "code_challenge_method=plain"
    }
})
