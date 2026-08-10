package com.acgcompass.data.remote.ai

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class OpenAiCompatibleProviderThinkingTest : StringSpec({
    val request = AiRequest(
        systemPrompt = "system",
        userContent = "user",
        model = "model",
    )

    "supported provider disables thinking by default" {
        val provider = provider(supportsThinking = true)
        val body = provider.buildRequestBody(request, model = "model", thinkingEnabled = false)

        body["model"]?.jsonPrimitive?.content shouldBe "model"
        body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content shouldBe "disabled"
    }

    "DeepSeek empty model falls back without replacing a saved selection" {
        resolveAiDefaultModel(ProviderId.DEEPSEEK.name, "") shouldBe DEEPSEEK_DEFAULT_MODEL
        resolveAiDefaultModel(ProviderId.DEEPSEEK.name, "deepseek-reasoner") shouldBe "deepseek-reasoner"
        resolveAiDefaultModel(ProviderId.OPENAI.name, "").shouldBe(null)
    }

    "explicit thinking preference omits the disabled extension" {
        val provider = provider(supportsThinking = true)
        val body = provider.buildRequestBody(request, model = "model", thinkingEnabled = true)

        body["thinking"] shouldBe null
    }

    "unsupported provider never receives a proprietary thinking field" {
        val provider = provider(supportsThinking = false)
        val body = provider.buildRequestBody(request, model = "model", thinkingEnabled = false)

        body["thinking"] shouldBe null
    }
})

private fun provider(supportsThinking: Boolean) = OpenAiCompatibleProvider(
    id = ProviderId.DEEPSEEK,
    capabilities = OpenAiProviderCapabilities(
        defaultBaseUrl = "https://example.invalid",
        structuredOutput = true,
        supportsThinking = supportsThinking,
    ),
    credentialSource = object : AiCredentialSource {
        override suspend fun load(): AiEndpointConfig = AiEndpointConfig()
    },
    httpCaller = AiHttpCaller(OkHttpClient()),
    json = Json { ignoreUnknownKeys = true },
)
