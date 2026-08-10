package com.acgcompass.data.remote.mal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * MyAnimeList OAuth2 令牌端点（`POST {oauthBase}token`）响应。
 *
 * Token 响应格式：`{ token_type:"Bearer", expires_in(秒，Access Token
 * 寿命 1 小时), access_token, refresh_token(寿命 1 个月) }`。
 *
 * **安全约束**：此 DTO 仅在内存中承载 token，由更高层写入用户本机 `CredentialStore`
 * （加密），**绝不**记录日志、写入 Room / DataStore / 默认备份。所有字段可空以做向后兼容解析。
 */
@Serializable
data class MalTokenResponseDto(
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
)
