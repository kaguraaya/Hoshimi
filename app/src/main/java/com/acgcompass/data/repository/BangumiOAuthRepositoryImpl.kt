package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.map
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SecretBundle
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.remote.bangumi.BangumiOAuthClient
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiTokenResponseDto
import com.acgcompass.domain.auth.BangumiAuthorization
import com.acgcompass.domain.auth.BangumiOAuthRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BangumiOAuthRepositoryImpl @Inject constructor(
    private val oauthClient: BangumiOAuthClient,
    private val credentialStore: CredentialStore,
    private val settingsDataStore: SettingsDataStore,
    private val bangumi: BangumiRemoteDataSource,
) : BangumiOAuthRepository {
    override fun isAvailable(): Boolean = oauthClient.isAvailable()

    override suspend fun buildAuthorization(state: String): AppResult<BangumiAuthorization> =
        oauthClient.buildAuthorizeUrl(state).map { request ->
            BangumiAuthorization(request.authorizeUrl, request.state)
        }

    override suspend fun exchangeCodeAndPersist(code: String, state: String): AppResult<Unit> =
        when (val result = oauthClient.exchangeCode(code, state)) {
            is AppResult.Failure -> result
            is AppResult.Success -> runCatchingApp {
                persistToken(result.data)
                settingsDataStore.setBangumiEnabled(true)
                fetchAndStoreUsername()
            }
        }

    private suspend fun persistToken(dto: BangumiTokenResponseDto) {
        val existing = runCatching { credentialStore.get(SourceId.BANGUMI) }.getOrNull()
        val expiresAt = dto.expiresIn?.let { System.currentTimeMillis() + it * MILLIS_PER_SECOND }
        credentialStore.put(
            SourceId.BANGUMI,
            SecretBundle(
                token = dto.accessToken,
                clientId = existing?.clientId,
                clientSecret = existing?.clientSecret,
                baseUrl = existing?.baseUrl,
                model = existing?.model,
                refreshToken = dto.refreshToken ?: existing?.refreshToken,
                tokenExpiresAt = expiresAt,
            ),
        )
    }

    private suspend fun fetchAndStoreUsername() {
        runCatching {
            val me = bangumi.getMe()
            if (me is AppResult.Success) {
                val name = me.data.username.ifBlank { me.data.nickname }
                if (name.isNotBlank()) settingsDataStore.setBangumiUsername(name)
            }
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
