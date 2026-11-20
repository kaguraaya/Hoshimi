package com.acgcompass.data.connectivity

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.remote.ai.AiProviderRegistry
import com.acgcompass.data.remote.ai.AiProviderSelector
import com.acgcompass.data.remote.ai.AiRequest
import com.acgcompass.data.remote.anilist.AniListRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.jikan.JikanRemoteDataSource
import com.acgcompass.data.remote.mal.MalRemoteDataSource
import com.acgcompass.data.remote.tenrai.TenraiRemoteDataSource
import com.acgcompass.data.remote.vndb.VndbRemoteDataSource
import com.acgcompass.domain.connectivity.ConnectionTarget
import com.acgcompass.domain.connectivity.ConnectionTestResult
import com.acgcompass.domain.connectivity.ConnectionTester
import com.acgcompass.domain.model.WorkMatch
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Singleton
@Suppress("LongParameterList", "TooManyFunctions")
class ConnectionTesterImpl @Inject constructor(
    private val bangumi: BangumiRemoteDataSource,
    private val anilist: AniListRemoteDataSource,
    private val jikan: JikanRemoteDataSource,
    private val tenrai: TenraiRemoteDataSource,
    private val mal: MalRemoteDataSource,
    private val vndb: VndbRemoteDataSource,
    private val credentialStore: CredentialStore,
    private val aiRegistry: AiProviderRegistry,
    private val aiSelector: AiProviderSelector,
    private val settingsDataStore: SettingsDataStore,
) : ConnectionTester {

    override suspend fun test(target: ConnectionTarget): ConnectionTestResult {
        val docUrl = target.documentationUrl.takeIf(String::isNotBlank)
        val configured = runCatchingCancellable {
            credentialStore.observeStatus().first()[target.toCredentialSource()]?.configured == true
        }.getOrDefault(false)

        return when {
            target == ConnectionTarget.BANGUMI -> testBangumi(configured, docUrl)
            target == ConnectionTarget.MAL && !mal.isEnabled() -> ConnectionTestResult(
                success = false,
                message = "未配置：需先填写并保存 Client ID 才能搜索 / 同步，详见官方文档",
                docUrl = docUrl,
            )
            target == ConnectionTarget.AI_PROVIDER -> testAi(configured)
            else -> testPublicSource(target, configured, docUrl)
        }
    }

    private suspend fun testAi(configured: Boolean): ConnectionTestResult = if (!configured) {
        ConnectionTestResult(false, "未配置：请先填写 API Key / Base URL / 模型名并保存")
    } else {
        when (val probe = withTimeoutOrNull(TIMEOUT_MS) { aiProbe() }) {
            null -> ConnectionTestResult(
                false,
                "连接超时（超过 $TIMEOUT_SECONDS 秒），请检查 Base URL / 网络",
            )
            is AppResult.Success -> ConnectionTestResult(true, "连接成功，模型可用")
            is AppResult.Failure -> ConnectionTestResult(false, "连接失败：${probe.error.cause}")
        }
    }

    private suspend fun testPublicSource(
        target: ConnectionTarget,
        configured: Boolean,
        docUrl: String?,
    ): ConnectionTestResult {
        val probe: AppResult<List<WorkMatch>>? = withTimeoutOrNull(TIMEOUT_MS) {
            runCatchingCancellable { sourceProbe(target) }.getOrElse { AppResult.Failure(AppError.Network()) }
        }

        return when (probe) {
            null -> ConnectionTestResult(
                success = false,
                message = "连接超时（超过 $TIMEOUT_SECONDS 秒），请检查网络后重试",
                docUrl = docUrl,
            )
            is AppResult.Success -> ConnectionTestResult(
                success = true,
                message = successMessage(target, configured),
                docUrl = docUrl,
            )
            is AppResult.Failure -> ConnectionTestResult(
                success = false,
                message = "连接失败：${probe.error.cause}",
                docUrl = docUrl,
            )
        }
    }

    private suspend fun sourceProbe(target: ConnectionTarget): AppResult<List<WorkMatch>> = when (target) {
        ConnectionTarget.BANGUMI -> bangumi.searchSubjects(keyword = PROBE_KEYWORD, limit = 1)
        ConnectionTarget.ANILIST -> anilist.searchMedia(keyword = PROBE_KEYWORD)
        ConnectionTarget.TENRAI -> tenrai.searchAnime(keyword = PROBE_KEYWORD, limit = 1)
        ConnectionTarget.JIKAN -> jikan.searchAnime(keyword = PROBE_KEYWORD, limit = 1)
        ConnectionTarget.MAL -> mal.searchAnime(keyword = PROBE_KEYWORD, limit = 1)
        ConnectionTarget.VNDB -> vndb.searchVn(keyword = PROBE_KEYWORD)
        ConnectionTarget.AI_PROVIDER -> AppResult.Success(emptyList())
    }

    private suspend fun testBangumi(configured: Boolean, docUrl: String?): ConnectionTestResult {
        val settings = runCatchingCancellable { settingsDataStore.settings.first() }.getOrNull()
        val official = settings?.isBangumiOfficialApi ?: true
        val currentUrl = settings?.bangumiApiBaseUrl.orEmpty()
        val addressType = when {
            official -> "官方 API"
            currentUrl.trimEnd('/') in COMMUNITY_BANGUMI_URLS -> "社区反代"
            else -> "自定义"
        }
        val lines = mutableListOf("当前地址：$addressType（${currentUrl.ifBlank { "?" }}）")

        val search = withTimeoutOrNull(TIMEOUT_MS) {
            runCatchingCancellable { bangumi.searchSubjects(keyword = PROBE_KEYWORD, limit = 1) }
                .getOrElse { AppResult.Failure(AppError.Network()) }
        }
        return when (search) {
            null -> bangumiPublicFailure(
                lines = lines,
                message = "✗ 地址不可访问 / 公共搜索：超时（>$TIMEOUT_SECONDS s）",
                official = official,
                docUrl = docUrl,
            )
            is AppResult.Failure -> bangumiPublicFailure(
                lines = lines,
                message = "✗ 公共搜索失败：${search.error.cause}",
                official = official,
                docUrl = docUrl,
            )
            is AppResult.Success -> {
                lines += "✓ 地址可访问，公共搜索可用"
                testBangumiPersonal(configured, official, settings?.bangumiNonOfficialTokenConsent, lines, docUrl)
            }
        }
    }

    private fun bangumiPublicFailure(
        lines: MutableList<String>,
        message: String,
        official: Boolean,
        docUrl: String?,
    ): ConnectionTestResult {
        lines += message
        if (official) {
            lines += "提示：当前网络可能无法访问官方 API，可检查网络/代理，或切换到社区反代 / 自定义地址。"
        }
        return ConnectionTestResult(false, lines.joinToString("\n"), docUrl)
    }

    private suspend fun testBangumiPersonal(
        configured: Boolean,
        official: Boolean,
        tokenConsent: Boolean?,
        lines: MutableList<String>,
        docUrl: String?,
    ): ConnectionTestResult {
        when {
            !configured -> lines += "· 个人同步未配置（未填 Token）"
            !official && tokenConsent != true ->
                lines += "· 非官方地址未确认 Token 风险：个人 Token 不会发送（仅公共搜索）。如需个人同步请在下方确认。"
            else -> appendBangumiPersonalProbe(lines)
        }
        return ConnectionTestResult(true, lines.joinToString("\n"), docUrl)
    }

    private suspend fun appendBangumiPersonalProbe(lines: MutableList<String>) {
        when (val me = withTimeoutOrNull(TIMEOUT_MS) { bangumi.getMe() }) {
            null -> lines += "✗ 用户信息：超时"
            is AppResult.Failure -> lines += "✗ Token / 用户信息：${me.error.cause}"
            is AppResult.Success -> {
                val username = me.data.username.ifBlank { me.data.nickname }
                lines += "✓ Token 有效，用户信息可读（@$username）"
                val collections = withTimeoutOrNull(TIMEOUT_MS) {
                    bangumi.getUserCollections(username = username, limit = 1)
                }
                when (collections) {
                    null -> lines += "✗ 收藏读取：超时"
                    is AppResult.Failure -> lines += "✗ 收藏读取：${collections.error.cause}"
                    is AppResult.Success -> lines += "✓ 收藏可同步（云端约 ${collections.data.total} 条）"
                }
            }
        }
    }

    private suspend fun aiProbe(): AppResult<Unit> = runCatchingApp {
        val provider = aiRegistry.get(aiSelector.selected())
            ?: throw AppError.Server(cause = "未注册可用的 AI 服务").asException()
        provider.complete(
            AiRequest(
                systemPrompt = "Connectivity check. Reply with: ok",
                userContent = "ping",
                model = "",
                maxTokens = 32,
            ),
        )
        Unit
    }

    private fun successMessage(target: ConnectionTarget, configured: Boolean): String = when (target) {
        ConnectionTarget.TENRAI, ConnectionTarget.JIKAN -> "公共搜索可用，无需 API Key"
        ConnectionTarget.BANGUMI, ConnectionTarget.ANILIST, ConnectionTarget.VNDB ->
            if (configured) "公共搜索可用，个人同步已就绪" else "公共搜索可用；个人同步未配置（可选填 Token 后启用）"
        else -> if (configured) "连接正常" else "公共搜索可用"
    }

    private fun ConnectionTarget.toCredentialSource(): SourceId = when (this) {
        ConnectionTarget.TENRAI -> SourceId.JIKAN
        else -> SourceId.valueOf(name)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val TIMEOUT_SECONDS = 10
        const val PROBE_KEYWORD = "fate"
        val COMMUNITY_BANGUMI_URLS = setOf("https://bgmapi.anibt.net", "https://api.bangumi.one")
    }
}
