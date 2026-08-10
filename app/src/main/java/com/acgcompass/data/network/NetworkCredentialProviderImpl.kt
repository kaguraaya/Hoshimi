package com.acgcompass.data.network

import com.acgcompass.core.network.NetworkCredential
import com.acgcompass.core.network.NetworkCredentialProvider
import com.acgcompass.core.network.NetworkSource
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class NetworkCredentialProviderImpl @Inject constructor(
    private val credentialStore: CredentialStore,
    private val settingsDataStore: SettingsDataStore,
) : NetworkCredentialProvider {
    private val invalidationMutex = Mutex()

    override suspend fun get(source: NetworkSource): NetworkCredential? =
        credentialStore.get(source.toCredentialSource())?.let { secret ->
            NetworkCredential(
                token = secret.token,
                apiKey = secret.apiKey,
                clientId = secret.clientId,
            )
        }

    override suspend fun onUnauthorized(source: NetworkSource) {
        if (source != NetworkSource.BANGUMI) return
        invalidationMutex.withLock {
            if (credentialStore.get(SourceId.BANGUMI) == null) return
            credentialStore.clear(SourceId.BANGUMI)
            settingsDataStore.clearBangumiUsername()
            settingsDataStore.setBangumiAuthNotice("Bangumi 登录已失效，请重新授权")
        }
    }

    private fun NetworkSource.toCredentialSource(): SourceId = when (this) {
        NetworkSource.BANGUMI -> SourceId.BANGUMI
        NetworkSource.ANILIST -> SourceId.ANILIST
        NetworkSource.TENRAI -> SourceId.JIKAN
        NetworkSource.JIKAN -> SourceId.JIKAN
        NetworkSource.MAL -> SourceId.MAL
        NetworkSource.VNDB -> SourceId.VNDB
        NetworkSource.AI_PROVIDER -> SourceId.AI_PROVIDER
    }
}
