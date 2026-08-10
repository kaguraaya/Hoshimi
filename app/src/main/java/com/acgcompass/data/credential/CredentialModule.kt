package com.acgcompass.data.credential

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 绑定：将 [CredentialStore] 契约绑定到加密实现 [CredentialStoreImpl]。
 *
 * 以 application 作用域单例提供，确保整个进程仅持有一个加密 prefs 实例，避免并发写入冲突。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CredentialModule {

    @Binds
    @Singleton
    abstract fun bindCredentialStore(impl: CredentialStoreImpl): CredentialStore
}
