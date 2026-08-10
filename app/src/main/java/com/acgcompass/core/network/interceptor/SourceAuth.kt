package com.acgcompass.core.network.interceptor

import com.acgcompass.core.network.NetworkCredential
import com.acgcompass.core.network.NetworkCredentialProvider
import com.acgcompass.core.network.NetworkSource
import com.acgcompass.core.network.RequestPriority
import okhttp3.Request

/**
 * 单源鉴权策略。
 *
 * 每个数据源 / AI 服务的鉴权方式不同（Bearer Token、自定义头、查询参数、Client-Id 头等），
 * [SourceAuth] 将「如何把某源的凭据注入到请求」抽象为一个小策略，由 [AuthInterceptor] 调用。
 *
 * **安全约束**：策略只读取由 [NetworkCredentialProvider] 提供的内存凭据并注入到出站请求，
 * **绝不**记录、缓存或回写凭据；当 [secret] 为 `null` 或缺少所需字段时，必须原样返回请求（透传）。
 */
interface SourceAuth {

    /** 该策略对应的凭据归属源。 */
    val sourceId: NetworkSource

    /** Foreground by default; workers and prefetchers explicitly opt into background priority. */
    val requestPriority: RequestPriority
        get() = RequestPriority.FOREGROUND

    /**
     * 将 [secret] 中的凭据注入 [request]。
     *
     * @param secret 该源的明文凭据；为 `null` 表示未配置，**必须**原样返回 [request]（透传）。
     * @return 注入鉴权后的新请求；无凭据时返回原请求。
     */
    fun authenticate(request: Request, secret: NetworkCredential?): Request
}

private class PrioritizedSourceAuth(
    private val delegate: SourceAuth,
    override val requestPriority: RequestPriority,
) : SourceAuth by delegate

fun SourceAuth.withPriority(priority: RequestPriority): SourceAuth =
    if (requestPriority == priority) this else PrioritizedSourceAuth(this, priority)

/**
 * 一组内置鉴权策略。各策略均为无状态，可作为单例复用。
 *
 * 具体请求头名称与格式以各数据源的最新官方文档为准。
 * 缺少凭据时保持原请求不变。
 */
object SourceAuths {

    private const val HEADER_AUTHORIZATION = "Authorization"

    /** 无鉴权（如 Jikan 公共 API，无需 key）：恒透传。 */
    class NoAuth(override val sourceId: NetworkSource) : SourceAuth {
        override fun authenticate(request: Request, secret: NetworkCredential?): Request = request
    }

    /**
     * `Authorization: <scheme> <token>` 型鉴权（Bearer / Token 等）。
     *
     * 当 [NetworkCredential.token] 为空白或 `null` 时透传。
     */
    class TokenHeaderAuth(
        override val sourceId: NetworkSource,
        private val scheme: String = "Bearer",
    ) : SourceAuth {
        override fun authenticate(request: Request, secret: NetworkCredential?): Request {
            val token = secret?.token?.takeIf { it.isNotBlank() }
            return if (token != null && request.header(HEADER_AUTHORIZATION) == null) {
                request.newBuilder()
                    .header(HEADER_AUTHORIZATION, "$scheme $token")
                    .build()
            } else {
                request
            }
        }
    }

    /**
     * MyAnimeList 官方 API：优先使用 OAuth `Bearer <token>`；仅有 Client ID 时退化为
     * `X-MAL-CLIENT-ID` 头（公共数据访问）。两者均缺失时透传。
     */
    class MalAuth(override val sourceId: NetworkSource = NetworkSource.MAL) : SourceAuth {
        override fun authenticate(request: Request, secret: NetworkCredential?): Request {
            val token = secret?.token?.takeIf { it.isNotBlank() }
            val clientId = secret?.clientId?.takeIf { it.isNotBlank() }
            return when {
                token != null && request.header(HEADER_AUTHORIZATION) == null -> request.newBuilder()
                    .header(HEADER_AUTHORIZATION, "Bearer $token")
                    .build()
                token != null -> request
                clientId != null && request.header(HEADER_MAL_CLIENT_ID) == null -> request.newBuilder()
                    .header(HEADER_MAL_CLIENT_ID, clientId)
                    .build()
                else -> request
            }
        }

        private companion object {
            const val HEADER_MAL_CLIENT_ID = "X-MAL-CLIENT-ID"
        }
    }

    /** Bangumi：Access Token / OAuth → `Authorization: Bearer <token>`。 */
    val bangumi: SourceAuth = TokenHeaderAuth(NetworkSource.BANGUMI, scheme = "Bearer")

    /** AniList：可选 Token（公共查询免鉴权）→ `Authorization: Bearer <token>`。 */
    val anilist: SourceAuth = TokenHeaderAuth(NetworkSource.ANILIST, scheme = "Bearer")

    /** Tenrai: public API, no credentials. */
    val tenrai: SourceAuth = NoAuth(NetworkSource.TENRAI)

    /** Jikan：无需 key，恒透传。 */
    val jikan: SourceAuth = NoAuth(NetworkSource.JIKAN)

    /** MAL 官方：OAuth Bearer 或 Client-Id 头。 */
    val mal: SourceAuth = MalAuth()

    /** VNDB：可选 Token → `Authorization: Token <token>`。 */
    val vndb: SourceAuth = TokenHeaderAuth(NetworkSource.VNDB, scheme = "Token")

    /** AI Provider：API Key → `Authorization: Bearer <apiKey>`。 */
    class ApiKeyHeaderAuth(
        override val sourceId: NetworkSource = NetworkSource.AI_PROVIDER,
        private val scheme: String = "Bearer",
    ) : SourceAuth {
        override fun authenticate(request: Request, secret: NetworkCredential?): Request {
            val key = secret?.apiKey?.takeIf { it.isNotBlank() }
            return if (key != null && request.header(HEADER_AUTHORIZATION) == null) {
                request.newBuilder()
                    .header(HEADER_AUTHORIZATION, "$scheme $key")
                    .build()
            } else {
                request
            }
        }
    }

    val aiProvider: SourceAuth = ApiKeyHeaderAuth()

    /** 按 [NetworkSource] 取内置策略。 */
    fun forSource(sourceId: NetworkSource): SourceAuth = when (sourceId) {
        NetworkSource.BANGUMI -> bangumi
        NetworkSource.ANILIST -> anilist
        NetworkSource.TENRAI -> tenrai
        NetworkSource.JIKAN -> jikan
        NetworkSource.MAL -> mal
        NetworkSource.VNDB -> vndb
        NetworkSource.AI_PROVIDER -> aiProvider
    }
}

/**
 * 将目标源标注到请求上，供 [AuthInterceptor] / [com.acgcompass.core.network.interceptor.RateLimitInterceptor]
 * 在共享 [okhttp3.OkHttpClient] 下识别请求归属的源。
 *
 * 使用 OkHttp 的类型化 tag：以 [SourceAuth] 接口为键写入策略实例，拦截器据此读取。
 * 各源的 Retrofit / GraphQL 调用在构造请求时调用本扩展打标即可。
 */
fun Request.Builder.networkSource(auth: SourceAuth): Request.Builder = tag(SourceAuth::class.java, auth)

/** 读取请求上标注的 [SourceAuth]（未标注返回 `null`）。 */
fun Request.sourceAuth(): SourceAuth? = tag(SourceAuth::class.java)
