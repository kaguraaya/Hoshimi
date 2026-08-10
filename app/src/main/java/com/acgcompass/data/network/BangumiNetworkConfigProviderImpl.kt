package com.acgcompass.data.network

import com.acgcompass.core.network.BangumiNetworkConfig
import com.acgcompass.core.network.BangumiNetworkConfigProvider
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.datastore.SettingsState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class BangumiNetworkConfigProviderImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : BangumiNetworkConfigProvider {
    override suspend fun get(): BangumiNetworkConfig {
        val settings = settingsDataStore.settings.first()
        val baseUrl = settings.bangumiApiBaseUrl.ifBlank { SettingsState.DEFAULT_BANGUMI_API_BASE_URL }
        return BangumiNetworkConfig(
            baseUrl = baseUrl,
            official = settings.isBangumiOfficialApi,
            allowTokenOnNonOfficial = settings.bangumiNonOfficialTokenConsent,
        )
    }
}
