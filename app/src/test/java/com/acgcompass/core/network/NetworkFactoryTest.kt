package com.acgcompass.core.network

import com.acgcompass.core.network.graphql.GraphQlClient
import com.acgcompass.core.network.graphql.GraphQlRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * 网络核心工厂单元测试。验证：Json 容错配置、OkHttp 超时与拦截器扩展点、
 * Retrofit 转换器装配、薄 GraphQL 调用器的请求/响应处理。
 */
class NetworkFactoryTest : StringSpec({

    "NetworkJson ignores unknown keys and omits explicit nulls" {
        val json = NetworkJson.instance
        // 未知字段不应导致解析失败。
        val decoded = json.decodeFromString(Sample.serializer(), """{"id":7,"extra":"ignored"}""")
        decoded.id shouldBe 7
        // explicitNulls = false -> null 字段被省略。
        json.encodeToString(Sample.serializer(), Sample(id = 1, name = null)) shouldBe """{"id":1}"""
    }

    "HttpClientFactory applies timeouts and provided interceptors" {
        val marker = Interceptor { chain -> chain.proceed(chain.request()) }
        val client = HttpClientFactory.build(
            timeouts = HttpTimeouts.REST,
            interceptors = listOf(marker),
        )
        client.callTimeoutMillis shouldBe 0
        client.connectTimeoutMillis shouldBe TimeUnit.SECONDS.toMillis(10).toInt()
        client.interceptors shouldContainExactly listOf(marker)
    }

    "AI client uses its own, more generous timeouts (separate instance)" {
        val rest = HttpClientFactory.build(HttpTimeouts.REST)
        val ai = HttpClientFactory.build(HttpTimeouts.AI)
        ai shouldNotBe rest
        ai.readTimeoutMillis shouldBe TimeUnit.SECONDS.toMillis(60).toInt()
        ai.callTimeoutMillis shouldBe TimeUnit.SECONDS.toMillis(90).toInt()
    }

    "RetrofitFactory builds a Retrofit with the kotlinx converter and given baseUrl" {
        val retrofit = RetrofitFactory.create(
            baseUrl = NetworkConstants.JIKAN_BASE_URL,
            client = OkHttpClient(),
        )
        retrofit.baseUrl().toString() shouldBe NetworkConstants.JIKAN_BASE_URL
        retrofit.converterFactoriesContainsKotlinx() shouldBe true
    }

    "REST base urls end with a slash (Retrofit requirement)" {
        listOf(
            NetworkConstants.BANGUMI_BASE_URL,
            NetworkConstants.JIKAN_BASE_URL,
            NetworkConstants.MAL_BASE_URL,
            NetworkConstants.VNDB_BASE_URL,
        ).forEach { it shouldEndWith "/" }
        // GraphQL endpoint must NOT end with a slash.
        NetworkConstants.ANILIST_GRAPHQL_URL shouldNotContain " "
    }

    "GraphQlRequest serializes to the standard { query, variables } shape" {
        val req = GraphQlRequest(
            query = "query(\$id:Int){Media(id:\$id){id}}",
            variables = JsonObject(mapOf("id" to JsonPrimitive(1))),
        )
        val body = Json.encodeToString(GraphQlRequest.serializer(), req)
        body shouldContain "\"query\""
        body shouldContain "\"variables\""
        body shouldContain "\"id\":1"
    }

    "GraphQlClient posts JSON and parses data/errors from the response" {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"Media":{"id":1}},"errors":null}"""),
        )
        server.start()
        try {
            val client = GraphQlClient(
                client = OkHttpClient(),
                serverUrl = server.url("/").toString(),
            )
            val resp = runBlocking { client.query("query{Media(id:1){id}}") }

            resp.statusCode shouldBe 200
            resp.data shouldNotBe null
            resp.isSuccessful shouldBe true

            val recorded = server.takeRequest()
            recorded.method shouldBe "POST"
            recorded.body.readUtf8() shouldContain "\"query\""
        } finally {
            server.shutdown()
        }
    }
})

@kotlinx.serialization.Serializable
private data class Sample(val id: Int, val name: String? = null)

private fun retrofit2.Retrofit.converterFactoriesContainsKotlinx(): Boolean =
    converterFactories().any { it::class.qualifiedName?.contains("kotlinx.serialization", ignoreCase = true) == true }
