package com.acgcompass.data.remote.bangumi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Bangumi OAuth2 令牌端点（`POST {oauthBase}access_token`）响应。
 *
 * 已核验（官方文档 `bangumi/api` How-to-Auth）：授权码 / 刷新均返回
 * `{ access_token, expires_in(秒，默认 604800=7天), token_type:"Bearer", refresh_token, user_id }`。
 *
 * 仅取 token 续期所需字段；`user_id` / `scope` 等由 `ignoreUnknownKeys` 跳过，避免类型意外。
 *
 * **安全约束**：此 DTO 仅在内存中承载 token，由更高层写入用户本机 `CredentialStore`（加密），
 * **绝不**记录日志、写入 Room / DataStore / 默认备份。所有字段可空以做向后兼容解析。
 */
@Serializable
data class BangumiTokenResponseDto(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
)
