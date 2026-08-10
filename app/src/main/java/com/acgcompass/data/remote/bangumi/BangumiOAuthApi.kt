package com.acgcompass.data.remote.bangumi

import retrofit2.Response
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Bangumi OAuth2 **令牌端点**接口（Base URL：
 * [com.acgcompass.core.network.NetworkConstants.BANGUMI_OAUTH_BASE_URL] = `https://bangumi.tv/oauth/`）。
 *
 * 仅承载授权码换取 / 刷新 Access Token 的 `application/x-www-form-urlencoded` 调用。
 *
 * 鉴权说明：令牌请求**不**走 `Authorization: Bearer` 注入，而是把 `client_id` / `client_secret` /
 * `code` / `refresh_token` 作为表单字段直接提交。因此这些调用**不打** Bangumi `@Tag`，
 * 既不会被 [com.acgcompass.core.network.interceptor.AuthInterceptor] 注入 token，
 * 也不会被公共 API 反代改写 host；令牌传输只使用官方端点或构建时注入的私有端点。
 *
 * **安全约束**：所有 client id / secret / code / token 均由调用方从 `CredentialStore`
 * 读取后以参数传入，**绝不**硬编码；返回的 token 仅由更高层写回加密存储，不入日志。
 *
 * 返回 [Response] 以便 [BangumiOAuthClient] 检视 HTTP 状态码并映射领域错误（不依赖异常路径）。
 */
@Suppress("LongParameterList")
interface BangumiOAuthApi {

    /** 用授权码换取 Access / Refresh Token（`grant_type=authorization_code`）。 */
    @FormUrlEncoded
    @POST
    suspend fun exchangeCode(
        @Url endpoint: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String? = null,
        @Field("code") code: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("state") state: String,
        @Field("grant_type") grantType: String = "authorization_code",
    ): Response<BangumiTokenResponseDto>

    /** 用 Refresh Token 续期 Access Token（`grant_type=refresh_token`）。 */
    @FormUrlEncoded
    @POST
    suspend fun refreshToken(
        @Url endpoint: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String? = null,
        @Field("refresh_token") refreshToken: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("grant_type") grantType: String = "refresh_token",
    ): Response<BangumiTokenResponseDto>
}
