package com.acgcompass.data.network

import com.acgcompass.core.network.BangumiNetworkConfigProvider
import com.acgcompass.core.network.NetworkCredentialProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBoundaryModule {
    @Binds
    @Singleton
    abstract fun bindNetworkCredentialProvider(impl: NetworkCredentialProviderImpl): NetworkCredentialProvider

    @Binds
    @Singleton
    abstract fun bindBangumiNetworkConfigProvider(impl: BangumiNetworkConfigProviderImpl): BangumiNetworkConfigProvider
}
