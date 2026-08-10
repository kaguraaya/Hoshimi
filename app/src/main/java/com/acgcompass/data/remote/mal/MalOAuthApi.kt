package com.acgcompass.data.remote.mal

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * MyAnimeList OAuth2 **令牌端点**接口（Base URL：
 * [com.acgcompass.core.network.NetworkConstants.MAL_OAUTH_BASE_URL]）。
 *
 * 仅承载 PKCE 授权码换取 / 刷新 Access Token 的 `application/x-www-form-urlencoded` 调用。
 *
 * 鉴权说明：令牌请求**不**走 `Authorization: Bearer` 注入（那是 API 调用用的），而是把
 * `client_id`（+ 可选 `client_secret`）/ `code` / `code_verifier` 作为表单字段直接提交
 * （Scheme 2，`client_auth`）。因此这些调用**不打** [com.acgcompass.core.network.interceptor.SourceAuth]
 * 标签，由 `AuthInterceptor` 透传。
 *
 * **安全约束**：所有 client id / secret / code / token 均由调用方从 `CredentialStore`
 * 读取后以参数传入，**绝不**硬编码；返回的 token 仅由更高层写回加密存储，不入日志。
 *
 * 返回 [Response] 以便 [MalOAuthClient] 检视 HTTP 状态码并映射领域错误（不依赖异常路径）。
 */
interface MalOAuthApi {

    /** 用授权码 + `code_verifier` 换取 Access / Refresh Token（PKCE，`grant_type=authorization_code`）。 */
    @FormUrlEncoded
    @POST("token")
    suspend fun exchangeCode(
        @Field("client_id") clientId: String,
        @Field("code") code: String,
        @Field("code_verifier") codeVerifier: String,
        @Field("grant_type") grantType: String = "authorization_code",
        @Field("redirect_uri") redirectUri: String? = null,
        @Field("client_secret") clientSecret: String? = null,
    ): Response<MalTokenResponseDto>

    /** 用 Refresh Token 续期 Access Token（`grant_type=refresh_token`）。 */
    @FormUrlEncoded
    @POST("token")
    suspend fun refreshToken(
        @Field("client_id") clientId: String,
        @Field("refresh_token") refreshToken: String,
        @Field("grant_type") grantType: String = "refresh_token",
        @Field("client_secret") clientSecret: String? = null,
    ): Response<MalTokenResponseDto>
}
