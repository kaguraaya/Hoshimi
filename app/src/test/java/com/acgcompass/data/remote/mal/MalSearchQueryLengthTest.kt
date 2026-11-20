package com.acgcompass.data.remote.mal

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.network.RetrofitFactory
import com.acgcompass.data.credential.CredentialStatus
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.RedactedCredentials
import com.acgcompass.data.credential.SecretBundle
import com.acgcompass.data.credential.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** MAL 官方搜索要求关键词至少 3 个字符：更短的标题不发请求，避免每次都收到 400 被记成失败。 */
class MalSearchQueryLengthTest : StringSpec({
    "two-character titles are not sent to MAL" {
        val server = MockWebServer().apply { start() }
        val source = source(server)

        val result = source.searchAnime("氷菓")

        (result as AppResult.Success).data shouldBe emptyList()
        server.requestCount shouldBe 0
        server.shutdown()
    }

    "three-character titles are searched" {
        val server = MockWebServer().apply {
            enqueue(MockResponse().setBody("""{"data":[]}"""))
            start()
        }

        source(server).searchAnime("Kanon")

        server.requestCount shouldBe 1
        server.shutdown()
    }
})

private fun source(server: MockWebServer) = MalRemoteDataSource(
    RetrofitFactory.create(server.url("/v2/").toString(), OkHttpClient()).create(MalApi::class.java),
    object : CredentialStore {
        override suspend fun put(source: SourceId, secret: SecretBundle) = Unit
        override suspend fun get(source: SourceId): SecretBundle? = SecretBundle(clientId = "client")
        override suspend fun clear(source: SourceId) = Unit
        override suspend fun clearAll() = Unit
        override fun observeStatus(): Flow<Map<SourceId, CredentialStatus>> = flowOf(emptyMap())
        override suspend fun exportRedacted(): RedactedCredentials = RedactedCredentials()
    },
)
