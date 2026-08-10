package com.acgcompass.data.remote.mal

import com.acgcompass.core.network.NetworkConstants
import java.security.SecureRandom
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * MyAnimeList OAuth2 **PKCE** 工具。
 *
 * MAL 官方目前**仅支持 `plain` 方法**（`code_challenge == code_verifier`，
 * `code_challenge_method=plain`）。本工具为**纯函数 / 无 IO 无凭据**：
 * - 生成 43–128 字符、仅含 RFC 7636 `unreserved` 字符集的 `code_verifier`；
 * - 由 `code_verifier` 推导 `code_challenge`（plain 方法下二者相等）；
 * - 拼接授权 URL（引导用户在浏览器登录授权）。
 *
 * **安全约束**：本工具**不**持有、读取或写入任何 client id / secret / token；
 * `clientId` 由调用方从 `CredentialStore` 读取后以参数传入，绝不在代码中硬编码。
 */
object MalPkce {

    /** RFC 7636 `code_challenge_method`：MAL 当前仅支持 `plain`。 */
    const val CODE_CHALLENGE_METHOD_PLAIN: String = "plain"

    /** `code_verifier` 长度下限（RFC 7636：43–128）。 */
    const val MIN_VERIFIER_LENGTH: Int = 43

    /** `code_verifier` 长度上限（RFC 7636：43–128）。 */
    const val MAX_VERIFIER_LENGTH: Int = 128

    /** RFC 7636 `unreserved` 字符集：`[A-Z] [a-z] [0-9] - . _ ~`。 */
    private const val UNRESERVED_CHARS: String =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    private val secureRandom: SecureRandom by lazy { SecureRandom() }

    /**
     * 生成符合 RFC 7636 的 `code_verifier`。
     *
     * @param length 期望长度，自动夹紧到 [[MIN_VERIFIER_LENGTH], [MAX_VERIFIER_LENGTH]]。
     * @param random 随机源（默认 [SecureRandom]，可注入以便测试确定性）。
     */
    fun generateCodeVerifier(length: Int = MAX_VERIFIER_LENGTH, random: SecureRandom = secureRandom): String {
        val clamped = length.coerceIn(MIN_VERIFIER_LENGTH, MAX_VERIFIER_LENGTH)
        return buildString(clamped) {
            repeat(clamped) {
                append(UNRESERVED_CHARS[random.nextInt(UNRESERVED_CHARS.length)])
            }
        }
    }

    /**
     * 由 `code_verifier` 推导 `code_challenge`。
     *
     * MAL 仅支持 `plain`，故 challenge 即 verifier 原值（不做 S256 哈希）。
     */
    fun codeChallenge(codeVerifier: String): String = codeVerifier

    /**
     * 拼接 OAuth2 授权 URL（`GET {oauthBase}authorize`），由调用方在浏览器 / 自定义标签页打开。
     *
     * @param clientId 用户在 `CredentialStore` 配置的 Client ID（**不得**硬编码）。
     * @param codeChallenge 由 [codeChallenge] 推导（plain 方法 == code_verifier）。
     * @param state 防 CSRF 的随机串；回调时**必须**由调用方校验。
     * @param redirectUri 可选回调地址；省略时使用应用注册的默认回调。
     */
    fun buildAuthorizeUrl(
        clientId: String,
        codeChallenge: String,
        state: String,
        redirectUri: String? = null,
    ): String {
        val builder = NetworkConstants.MAL_OAUTH_BASE_URL.toHttpUrl().newBuilder()
            .addPathSegment("authorize")
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", CODE_CHALLENGE_METHOD_PLAIN)
        if (!redirectUri.isNullOrBlank()) {
            builder.addQueryParameter("redirect_uri", redirectUri)
        }
        return builder.build().toString()
    }
}
