package com.acgcompass.data.remote.bangumi

import com.acgcompass.core.common.AppResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import okhttp3.HttpUrl.Companion.toHttpUrl
import retrofit2.Response

class BangumiOAuthClientTest : StringSpec({
    "missing build credentials disable authorization without a network request" {
        val api = RecordingOAuthApi()
        val client = BangumiOAuthClient(api, defaultClientId = "", defaultClientSecret = "", oauthProxyBaseUrl = "")

        (client.buildAuthorizeUrl("state") is AppResult.Failure) shouldBe true
        (client.exchangeCode("code", "state") is AppResult.Failure) shouldBe true
        api.exchangeCalls shouldBe 0
    }

    "authorization URL uses only the official host exact callback and caller state" {
        val client = BangumiOAuthClient(RecordingOAuthApi(), "client-id", "client-secret", "")
        val request = (client.buildAuthorizeUrl("csrf-state") as AppResult.Success).data
        val url = request.authorizeUrl.toHttpUrl()

        url.host shouldBe com.acgcompass.core.network.NetworkConstants.BANGUMI_OAUTH_BASE_URL.toHttpUrl().host
        url.encodedPath shouldBe "/oauth/authorize"
        url.queryParameter("client_id") shouldBe "client-id"
        url.queryParameter("redirect_uri") shouldBe
            com.acgcompass.core.network.NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI
        url.queryParameter("state") shouldBe "csrf-state"
    }

    "HTTPS proxy changes authorization and token transport endpoints" {
        val client = BangumiOAuthClient(
            RecordingOAuthApi(),
            "client-id",
            "client-secret",
            "https://oauth.example",
        )

        client.tokenEndpoint() shouldBe "https://oauth.example/oauth/access_token"
        val authorizeUrl = (client.buildAuthorizeUrl("state") as AppResult.Success).data.authorizeUrl.toHttpUrl()
        authorizeUrl.host shouldBe "oauth.example"
        authorizeUrl.encodedPath shouldBe "/oauth/authorize"
    }

    "proxy configuration enables OAuth without embedding a client secret" {
        val client = BangumiOAuthClient(RecordingOAuthApi(), "client-id", "", "https://oauth.example/oauth/")

        client.isAvailable() shouldBe true
        client.tokenEndpoint() shouldBe "https://oauth.example/oauth/access_token"
    }

    "non HTTPS proxy configuration falls back to the official token endpoint" {
        val client = BangumiOAuthClient(
            RecordingOAuthApi(),
            "client-id",
            "client-secret",
            "http://oauth.example/private/",
        )

        client.tokenEndpoint() shouldBe
            com.acgcompass.core.network.NetworkConstants.BANGUMI_OAUTH_BASE_URL
                .toHttpUrl()
                .newBuilder()
                .addPathSegment("access_token")
                .build()
                .toString()
    }
})

private class RecordingOAuthApi : BangumiOAuthApi {
    var exchangeCalls: Int = 0

    override suspend fun exchangeCode(
        endpoint: String,
        clientId: String,
        clientSecret: String?,
        code: String,
        redirectUri: String,
        state: String,
        grantType: String,
    ): Response<BangumiTokenResponseDto> {
        exchangeCalls++
        return Response.success(BangumiTokenResponseDto(accessToken = "token"))
    }

    override suspend fun refreshToken(
        endpoint: String,
        clientId: String,
        clientSecret: String?,
        refreshToken: String,
        redirectUri: String,
        grantType: String,
    ): Response<BangumiTokenResponseDto> = Response.success(BangumiTokenResponseDto(accessToken = "token"))
}
