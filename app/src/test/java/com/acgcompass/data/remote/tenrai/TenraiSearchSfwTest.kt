package com.acgcompass.data.remote.tenrai

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.network.RetrofitFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/**
 * Tenrai 的 `sfw=true` 会把 MAL 分级 R+ 的普通作品（例如《生徒会にも穴はある！》）过滤掉：
 * 给本地作品找对应条目时不能带这个参数；连通性测试等其它调用保持原样。
 */
class TenraiSearchSfwTest : StringSpec({
    val body = """{"pagination":{"has_next_page":false},"data":[{"mal_id":61578,""" +
        """"title":"Seitokai ni mo Ana wa Aru!","title_japanese":"生徒会にも穴はある！",""" +
        """"type":"TV","episodes":12,"year":2026,"score":7.76,"scored_by":11506}]}"""

    fun source(server: MockWebServer) = TenraiRemoteDataSource(
        RetrofitFactory.create(server.url("/v1/").toString(), OkHttpClient()).create(TenraiApi::class.java),
    )

    "matching search omits sfw so R+ titles are found" {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(body)) }
        server.start()
        val result = source(server).searchAnime("生徒会にも穴はある！", safeForWork = false)
        val url = server.takeRequest().requestUrl.toString()
        url shouldNotContain "sfw"
        (result as AppResult.Success).data.single().work.id shouldBe "TENRAI:61578"
        server.shutdown()
    }

    "default search still sends sfw=true" {
        val server = MockWebServer().apply { enqueue(MockResponse().setBody(body)) }
        server.start()
        source(server).searchAnime("test")
        server.takeRequest().requestUrl.toString() shouldContain "sfw=true"
        server.shutdown()
    }
})
