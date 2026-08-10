package com.acgcompass.core.network

/**
 * 公共数据源端点常量。
 *
 * 这里 **只** 放置无需鉴权即可拼接的公开 Base URL / GraphQL endpoint，
 * **绝不**包含任何 key / token / secret —— 所有凭据由用户在设置页自行提供，
 * 运行时由 `AuthInterceptor`从 `CredentialStore` 注入。
 *
 * > 注意：下列 URL 为基于既有官方文档的设计假设，接入各源前
 * > 上线前必须核验各服务的最新官方文档，以核验结果为准。
 *
 * Retrofit 约定：`baseUrl` 必须以 `/` 结尾；GraphQL `serverUrl` 不带尾斜杠。
 */
object NetworkConstants {

    /** Bangumi 主来源 REST API；鉴权与 User-Agent 由拦截器注入。 */
    const val BANGUMI_BASE_URL: String = "https://api.bgm.tv/"

    /**
     * Bangumi 新版私有 API（next.bgm.tv/p1）。仅用于公共「条目短评」JSON 接口
     * （`GET p1/subjects/{id}/comments`，匿名可读、非网页抓取）。固定官方地址，不随 Bangumi 反代设置改写。
     */
    const val BANGUMI_NEXT_BASE_URL: String = "https://next.bgm.tv/p1/"

    /**
     * Bangumi OAuth2 授权和令牌端点；应用凭据由构建配置注入：
     * - 授权：`GET {base}authorize`（在应用内 WebView 打开，引导用户登录授权）；
     * - 令牌：`POST {base}access_token`（`application/x-www-form-urlencoded`，换 / 刷新 token）。
     *
     * 固定官方地址，**不**随 Bangumi 反代设置改写（令牌端点请求不打 Bangumi `@Tag`，
     * 故 [com.acgcompass.core.network.interceptor.BangumiBaseUrlInterceptor] 不会改写其 host）。
     * 不含任何 client id / secret —— 由用户在 `CredentialStore` 配置后于运行时拼接。
     */
    const val BANGUMI_OAUTH_BASE_URL: String = "https://bangumi.tv/oauth/"

    /**
     * Bangumi OAuth 回调地址（自定义 scheme）。由授权 WebView 内部拦截，**无需**系统 deep-link / Manifest 注册。
     * 用户须在 `bgm.tv/dev/app` 应用后台将「回调地址」设置为**完全相同**的此值，否则 Bangumi 拒绝换取 token。
     */
    const val BANGUMI_OAUTH_REDIRECT_URI: String = "hoshimi://oauth/bangumi/callback"

    /** AniList GraphQL 补充来源，公共查询免鉴权。 */
    const val ANILIST_GRAPHQL_URL: String = "https://graphql.anilist.co"

    /** Jikan REST 补充来源，无需凭据。 */
    const val JIKAN_BASE_URL: String = "https://api.jikan.moe/v4/"

    /** Tenrai's Jikan-compatible public API used for supplementary anime ratings. */
    const val TENRAI_BASE_URL: String = "https://api.tenrai.org/v1/"

    /** MyAnimeList 官方 REST API，用户显式配置 OAuth2 PKCE 后启用。 */
    const val MAL_BASE_URL: String = "https://api.myanimelist.net/v2/"

    /**
     * MyAnimeList OAuth2 PKCE 授权和令牌端点；与 [MAL_BASE_URL] 使用不同域名：
     * - 授权：`GET {base}authorize`（引导用户，浏览器打开）；
     * - 令牌：`POST {base}token`（`application/x-www-form-urlencoded`，换 / 刷新 token）。
     *
     * 不含任何 client id / secret —— 由用户在 `CredentialStore` 配置后于运行时拼接。
     */
    const val MAL_OAUTH_BASE_URL: String = "https://myanimelist.net/v1/oauth2/"

    /** VNDB HTTP API，使用 POST/JSON 并支持可选 Token。 */
    const val VNDB_BASE_URL: String = "https://api.vndb.org/kana/"
}
