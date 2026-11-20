package com.acgcompass.data.remote.ai

import com.acgcompass.core.common.AppErrorException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * 常见 OpenAI 兼容端点的模拟响应：标准返回、只有思维链的推理模型、点名拒绝某个参数的 400。
 * 服务端拒绝 `response_format` / `max_tokens` / `temperature` 时去掉或换写法再试一次，
 * 仍失败时错误说明里点名是哪个参数。
 */
class OpenAiCompatibleEndpointsTest : StringSpec({
    val schemaRequest = AiRequest(
        systemPrompt = "system",
        userContent = "user",
        model = "model",
        responseFormat = AiResponseFormat.JsonSchema(name = "out", schema = buildJsonObject { put("type", "object") }),
        maxTokens = 512,
    )

    fun ok(content: String) = MockResponse().setBody(
        """{"choices":[{"message":{"content":${Json.encodeToString(String.serializer(), content)}},""" +
            """"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}""",
    )

    fun badRequest(message: String) = MockResponse().setResponseCode(
        400,
    ).setBody("""{"error":{"message":"$message","type":"invalid_request_error"}}""")

    "standard response is parsed" {
        withServer(ok("{\"a\":1}")) { server, provider ->
            provider.complete(schemaRequest).content shouldBe "{\"a\":1}"
            server.takeRequest().body.readUtf8() shouldContain "json_schema"
        }
    }

    "reasoning-only reply falls back to reasoning_content" {
        val reply = MockResponse().setBody(
            """{"choices":[{"message":{"content":"","reasoning_content":"thinking text"},"finish_reason":"length"}]}""",
        )
        withServer(reply) { _, provider -> provider.complete(schemaRequest).content shouldBe "thinking text" }
    }

    "rejected response_format is dropped and retried once" {
        withServer(badRequest("response_format is not supported by this model"), ok("plain")) { server, provider ->
            provider.complete(schemaRequest).content shouldBe "plain"
            server.takeRequest().body.readUtf8() shouldContain "response_format"
            server.takeRequest().body.readUtf8() shouldNotContain "response_format"
        }
    }

    "max_tokens rejected by newer models is sent as max_completion_tokens" {
        val message = "Unsupported parameter: 'max_tokens' is not supported with this model. " +
            "Use 'max_completion_tokens' instead."
        withServer(badRequest(message), ok("done")) { server, provider ->
            provider.complete(schemaRequest).content shouldBe "done"
            server.takeRequest()
            val retried = server.takeRequest().body.readUtf8()
            retried shouldContain "\"max_completion_tokens\":512"
            retried shouldNotContain "\"max_tokens\""
        }
    }

    "error names the rejected parameter when retries run out" {
        val responses = Array(3) { badRequest("temperature does not support 0.2 with this model") }
        withServer(*responses) { _, provider ->
            val error = shouldThrow<AppErrorException> { provider.complete(schemaRequest) }
            error.error.cause shouldContain "已自动去掉 temperature"
        }
    }

    "server errors are not rewritten" {
        withServer(MockResponse().setResponseCode(500).setBody("{}")) { server, provider ->
            shouldThrow<AppErrorException> { provider.complete(schemaRequest) }
            server.requestCount shouldBe 1
        }
    }
})

private suspend fun withServer(
    vararg responses: MockResponse,
    block: suspend (MockWebServer, OpenAiCompatibleProvider) -> Unit,
) {
    val server = MockWebServer()
    responses.forEach(server::enqueue)
    server.start()
    try {
        val provider = OpenAiCompatibleProvider(
            id = ProviderId.CUSTOM_OPENAI_COMPAT,
            capabilities = OpenAiProviderCapabilities(defaultBaseUrl = null, structuredOutput = true),
            credentialSource = object : AiCredentialSource {
                override suspend fun load(): AiEndpointConfig = AiEndpointConfig(
                    apiKey = "test-key",
                    baseUrl = server.url("/v1").toString(),
                    defaultModel = "model",
                )
            },
            httpCaller = AiHttpCaller(OkHttpClient()),
            json = Json { ignoreUnknownKeys = true },
        )
        block(server, provider)
    } finally {
        server.shutdown()
    }
}
