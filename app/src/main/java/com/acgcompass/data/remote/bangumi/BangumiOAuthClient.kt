package com.acgcompass.data.remote.bangumi

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.network.HttpErrorMapper
import com.acgcompass.core.network.NetworkConstants
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response

/**
 * Bangumi OAuth2 授权码流程客户端。Bangumi 不支持 PKCE，必须提供 `client_id` 和 `client_secret`。
 * 应用凭据仅来自 BuildConfig，由本地或 CI 构建配置注入。
 *
 * 职责：
 * 1. [buildAuthorizeUrl]：使用构建时注入的 App ID（`clientId`），
 *    拼接授权 URL（在应用内 WebView 打开，回调地址固定 [NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI]）。
 * 2. [exchangeCode] / [refreshToken]：调用 [BangumiOAuthApi] 令牌端点换取 / 续期 token。
 *
 * **启用门控**：未配置 App ID / Secret 时直接以 [AppError.Unauthorized] 短路，**不发请求**。
 *
 * **安全约束**：本类不持久化任何凭据；token 交换结果由调用方写回加密凭据存储，
 * 绝不记录日志 / 写入 Room / 默认备份。`state` 校验、token 落盘由更高层（授权流程）负责。
 */
@Singleton
class BangumiOAuthClient @Inject constructor(
    private val oauthApi: BangumiOAuthApi,
    @Named("bangumiDefaultClientId") private val defaultClientId: String,
    @Named("bangumiDefaultClientSecret") private val defaultClientSecret: String,
    @Named("bangumiOAuthProxyBaseUrl") private val oauthProxyBaseUrl: String,
) {

    fun isAvailable(): Boolean =
        defaultClientId.isNotBlank() && (proxyUrl() != null || defaultClientSecret.isNotBlank())

    /** 一次授权请求的非敏感产物：授权 URL + 防 CSRF `state`（调用方在回调时校验）。 */
    data class AuthorizationRequest(
        val authorizeUrl: String,
        val state: String,
    )

    /**
     * 构造授权 URL（`GET {oauthBase}authorize?...`），由调用方在 WebView 打开。
     *
     * @param state 防 CSRF 随机串（调用方生成并在回调时校验）。
     * @return [AppResult.Success] 携带 [AuthorizationRequest]；未配置 App ID 时
     *   [AppResult.Failure] 携带 [AppError.Unauthorized]（引导用户到设置页填写）。
     */
    suspend fun buildAuthorizeUrl(state: String): AppResult<AuthorizationRequest> = runCatchingApp {
        val clientId = requireClientId()
        val url = (proxyUrl() ?: NetworkConstants.BANGUMI_OAUTH_BASE_URL.toHttpUrl()).newBuilder()
            .addPathSegment("authorize")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI)
            .addQueryParameter("state", state)
            .build()
            .toString()
        AuthorizationRequest(authorizeUrl = url, state = state)
    }

    /** 用授权码换取 token（`grant_type=authorization_code`）。 */
    suspend fun exchangeCode(code: String, state: String): AppResult<BangumiTokenResponseDto> = runCatchingApp {
        oauthApi.exchangeCode(
            endpoint = tokenEndpoint(),
            clientId = requireClientId(),
            clientSecret = configuredClientSecret(),
            code = code,
            redirectUri = NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI,
            state = state,
        ).tokenOrThrow()
    }

    /** 用 Refresh Token 续期 Access Token（`grant_type=refresh_token`）。 */
    suspend fun refreshToken(refreshToken: String): AppResult<BangumiTokenResponseDto> = runCatchingApp {
        oauthApi.refreshToken(
            endpoint = tokenEndpoint(),
            clientId = requireClientId(),
            clientSecret = configuredClientSecret(),
            refreshToken = refreshToken,
            redirectUri = NetworkConstants.BANGUMI_OAUTH_REDIRECT_URI,
        ).tokenOrThrow()
    }

    /** Build credentials are mandatory for one-click OAuth; missing values never reach the network. */
    private fun requireClientId(): String {
        return defaultClientId.takeIf { it.isNotBlank() }
            ?: throw AppError.Unauthorized(cause = "此构建未启用 Bangumi 一键登录").asException()
    }

    private fun configuredClientSecret(): String? {
        if (proxyUrl() != null) return null
        return defaultClientSecret.takeIf { it.isNotBlank() }
            ?: throw AppError.Unauthorized(cause = "此构建未启用 Bangumi 一键登录").asException()
    }

    internal fun tokenEndpoint(): String {
        val base = proxyUrl() ?: NetworkConstants.BANGUMI_OAUTH_BASE_URL.toHttpUrl()
        return base.newBuilder().addPathSegment("access_token").build().toString()
    }

    private fun proxyUrl(): HttpUrl? {
        val parsed = oauthProxyBaseUrl.trim().takeIf(String::isNotEmpty)
            ?.toHttpUrlOrNull()
            ?.takeIf { it.isHttps }
            ?: return null
        val path = parsed.encodedPath.trimEnd('/')
        return if (path.isEmpty() || path == "/") {
            parsed.newBuilder().addPathSegment("oauth").build()
        } else {
            parsed
        }
    }

    /**
     * 解析令牌响应：非 2xx 经 [HttpErrorMapper.mapStatusCode] 抛出领域错误（如 `400` 参数错误 /
     * `401` 凭据无效）；2xx 但缺 `access_token` 抛出 [AppError.FieldMissing]。
     */
    private fun Response<BangumiTokenResponseDto>.tokenOrThrow(): BangumiTokenResponseDto {
        if (!isSuccessful) throw HttpErrorMapper.mapStatusCode(code()).asException()
        val body = body() ?: throw AppError.FieldMissing().asException()
        if (body.accessToken.isNullOrBlank()) throw AppError.FieldMissing().asException()
        return body
    }
}
