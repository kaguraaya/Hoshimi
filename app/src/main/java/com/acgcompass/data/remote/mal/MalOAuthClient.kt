package com.acgcompass.data.remote.mal

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.network.HttpErrorMapper
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

/**
 * MyAnimeList OAuth2 **PKCE** 客户端。
 *
 * 职责：
 * 1. [buildAuthorizeUrl]：读取用户在 [CredentialStore] 配置的 Client ID（**不得**硬编码），
 *    生成 `code_verifier` / `code_challenge`（plain）并拼接授权 URL（见 [MalPkce]）。
 * 2. [exchangeCode] / [refreshToken]：调用 [MalOAuthApi] 令牌端点换取 / 续期 token，
 *    可选携带用户配置的 Client Secret（缺省即「公共客户端」，仅 client_id + code_verifier）。
 *
 * **启用门控**：所有方法在未配置 Client ID 时直接以 [AppError.Unauthorized] 短路，
 * **不发请求**，与「仅用户显式配置后启用」一致。
 *
 * **安全约束**：本类不持久化任何凭据；token 交换结果由调用方写回加密 [CredentialStore]，
 * 绝不记录日志 / 写入 Room / 默认备份。`state` 校验、token 落盘由更高层（设置页授权流程）负责。
 */
@Singleton
class MalOAuthClient @Inject constructor(
    private val oauthApi: MalOAuthApi,
    private val credentialStore: CredentialStore,
) {

    /**
     * 一次 PKCE 授权请求的非敏感产物：授权 URL + `code_verifier` + `state`。
     *
     * 调用方应将 [codeVerifier] / [state] 暂存（内存或加密存储），在回调时分别用于
     * [exchangeCode] 与 `state` 校验（防 CSRF）。
     */
    data class AuthorizationRequest(
        val authorizeUrl: String,
        val codeVerifier: String,
        val state: String,
    )

    /**
     * 构造授权请求（生成 PKCE 参数 + 授权 URL）。
     *
     * @param state 防 CSRF 随机串（由调用方生成并在回调时校验）。
     * @param redirectUri 可选回调地址（省略则用应用注册的默认回调）。
     * @return [AppResult.Success] 携带 [AuthorizationRequest]；未配置 Client ID 时
     *   [AppResult.Failure] 携带 [AppError.Unauthorized]（引导用户到设置页）。
     */
    suspend fun buildAuthorizeUrl(state: String, redirectUri: String? = null): AppResult<AuthorizationRequest> =
        runCatchingApp {
            val clientId = requireClientId()
            val verifier = MalPkce.generateCodeVerifier()
            val challenge = MalPkce.codeChallenge(verifier)
            AuthorizationRequest(
                authorizeUrl = MalPkce.buildAuthorizeUrl(
                    clientId = clientId,
                    codeChallenge = challenge,
                    state = state,
                    redirectUri = redirectUri,
                ),
                codeVerifier = verifier,
                state = state,
            )
        }

    /**
     * 用授权码 + `code_verifier` 换取 token（PKCE）。可选 Client Secret 由 [CredentialStore] 提供。
     *
     * @param code 回调返回的授权码。
     * @param codeVerifier [buildAuthorizeUrl] 暂存的 `code_verifier`。
     * @param redirectUri 与授权时一致的回调地址（如有）。
     */
    suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        redirectUri: String? = null,
    ): AppResult<MalTokenResponseDto> = runCatchingApp {
        val clientId = requireClientId()
        val clientSecret = readClientSecret()
        oauthApi.exchangeCode(
            clientId = clientId,
            code = code,
            codeVerifier = codeVerifier,
            redirectUri = redirectUri,
            clientSecret = clientSecret,
        ).tokenOrThrow()
    }

    /**
     * 用 Refresh Token 续期 Access Token。可选 Client Secret 由 [CredentialStore] 提供。
     */
    suspend fun refreshToken(refreshToken: String): AppResult<MalTokenResponseDto> = runCatchingApp {
        val clientId = requireClientId()
        val clientSecret = readClientSecret()
        oauthApi.refreshToken(
            clientId = clientId,
            refreshToken = refreshToken,
            clientSecret = clientSecret,
        ).tokenOrThrow()
    }

    /** 读取用户配置的 Client ID；未配置时抛出 [AppError.Unauthorized]（短路、不发请求）。 */
    private suspend fun requireClientId(): String =
        credentialStore.get(SourceId.MAL)?.clientId?.takeIf { it.isNotBlank() }
            ?: throw AppError.Unauthorized(cause = "未配置 MyAnimeList Client ID").asException()

    /** 读取可选 Client Secret（公共客户端可缺省）。 */
    private suspend fun readClientSecret(): String? =
        credentialStore.get(SourceId.MAL)?.clientSecret?.takeIf { it.isNotBlank() }

    /**
     * 解析令牌响应：非 2xx 经 [HttpErrorMapper.mapStatusCode] 抛出领域错误（如 `400` 参数错误 /
     * `401` 凭据无效）；2xx 但缺 `access_token` 抛出 [AppError.FieldMissing]。
     */
    private fun Response<MalTokenResponseDto>.tokenOrThrow(): MalTokenResponseDto {
        if (!isSuccessful) throw HttpErrorMapper.mapStatusCode(code()).asException()
        val body = body() ?: throw AppError.FieldMissing().asException()
        if (body.accessToken.isNullOrBlank()) throw AppError.FieldMissing().asException()
        return body
    }
}
