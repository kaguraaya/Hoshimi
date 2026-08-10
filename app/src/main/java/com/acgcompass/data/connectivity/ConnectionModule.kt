package com.acgcompass.data.connectivity

import com.acgcompass.domain.connectivity.ConnectionTester
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ConnectionModule {
    @Binds
    @Singleton
    abstract fun bindConnectionTester(implementation: ConnectionTesterImpl): ConnectionTester
}
