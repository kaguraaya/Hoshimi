package com.acgcompass.data.remote.bangumi

import com.acgcompass.core.common.AppResult
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bangumi OAuth token **启动期自动续期器**。
 *
 * 在应用启动时调用 [refreshIfNeeded]：当本地存有 OAuth `refresh_token`，且 access token 已过期
 * 或将在 [REFRESH_THRESHOLD_MS] 内过期时，用 `refresh_token` 静默换取新 token 并写回加密存储。
 *
 * 纯客户端方案（无后端中转）：依赖用户已通过 [com.acgcompass.feature.auth.BangumiOAuthViewModel]
 * 完成一次 OAuth 登录、拿到 `refresh_token`。手动粘贴的 PAT（无 `refresh_token`）不受影响、原样保留。
 *
 * **安全**：token 仅在内存流转并写入加密存储，绝不记录日志。失败静默——续期失败不影响
 * 既有（可能仍有效的）token，用户仍可手动重新登录。
 */
@Singleton
class BangumiTokenRefresher @Inject constructor(
    private val oauthClient: BangumiOAuthClient,
    private val credentialStore: CredentialStore,
    private val settingsDataStore: SettingsDataStore,
) {
    private val mutex = Mutex()

    /** 按需续期：无 refresh_token / 仍很新则直接返回；best-effort，任何异常静默吞掉。 */
    suspend fun refreshIfNeeded() = mutex.withLock {
        val secret = runCatching { credentialStore.get(SourceId.BANGUMI) }.getOrNull() ?: return@withLock
        val refreshToken = secret.refreshToken?.takeIf { it.isNotBlank() } ?: return@withLock
        val expiresAt = secret.tokenExpiresAt
        val now = System.currentTimeMillis()
        // 纯客户端「打开即续期」模型下，Bangumi OAuth access token 固定 7 天、无法申请更长时长，
        // 只能靠 refresh_token 在用户打开应用时续期。为把有效期窗口拉到最长——只要 token 已超过约 1 天
        // （剩余 < REFRESH_THRESHOLD_MS）就在本次打开续期，使每次打开近乎必续、每天最多续一次（当天再开时
        // token 已刷新回 7 天、剩余 > 阈值即跳过）。如此只要用户在任意 7 天窗口内打开过一次，token 就不过期。
        // 未知过期时刻一律尝试续期。
        if (expiresAt != null && expiresAt - now > REFRESH_THRESHOLD_MS) return@withLock
        val gate = settingsDataStore.bangumiOAuthRefreshGate.first()
        if (gate.attemptedExpiresAt == expiresAt && gate.retryAt > now) return@withLock

        val result = oauthClient.refreshToken(refreshToken)
        if (result is AppResult.Success) {
            val dto = result.data
            credentialStore.put(
                SourceId.BANGUMI,
                secret.copy(
                    token = dto.accessToken ?: secret.token,
                    refreshToken = dto.refreshToken ?: secret.refreshToken,
                    tokenExpiresAt = dto.expiresIn?.let { now + it * MILLIS_PER_SECOND } ?: secret.tokenExpiresAt,
                ),
            )
            settingsDataStore.clearOAuthRefreshGate()
        } else if (result is AppResult.Failure) {
            if (result.error is com.acgcompass.core.common.AppError.Unauthorized) {
                credentialStore.clear(SourceId.BANGUMI)
                settingsDataStore.clearBangumiUsername()
                settingsDataStore.setBangumiAuthNotice("Bangumi 登录已失效，请重新授权")
                settingsDataStore.clearOAuthRefreshGate()
            } else {
                val failures = (gate.failureCount + 1).coerceAtMost(MAX_FAILURE_COUNT)
                val delay = (INITIAL_RETRY_DELAY_MS * (1L shl (failures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)))
                    .coerceAtMost(MAX_RETRY_DELAY_MS)
                settingsDataStore.recordOAuthRefreshFailure(expiresAt, now + delay, failures)
            }
        }
    }

    private companion object {
        /**
         * 剩余寿命少于 3 天即续期（Bangumi access token 默认 7 天），兼顾连续使用和代理配额。
         */
        const val REFRESH_THRESHOLD_MS: Long = 3L * 24 * 60 * 60 * 1000
        const val MILLIS_PER_SECOND = 1_000L
        const val MAX_FAILURE_COUNT = 8
        const val MAX_BACKOFF_SHIFT = 4
        const val INITIAL_RETRY_DELAY_MS = 15L * 60 * 1_000
        const val MAX_RETRY_DELAY_MS = 6L * 60 * 60 * 1_000
    }
}
