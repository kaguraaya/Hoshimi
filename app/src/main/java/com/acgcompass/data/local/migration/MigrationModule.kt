package com.acgcompass.data.local.migration

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the database migration safety net.
 *
 * - [MigrationVersionStore] → [DataStoreMigrationVersionStore] (DataStore-backed version bookkeeping).
 * - [BackupGuard] → [NoOpBackupGuard] is the default no-op binding.
 *
 * [DatabasePreMigrationBackup] is `@Inject`-constructed and consumes these bindings.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MigrationModule {

    @Binds
    @Singleton
    abstract fun bindMigrationVersionStore(impl: DataStoreMigrationVersionStore): MigrationVersionStore

    @Binds
    @Singleton
    abstract fun bindBackupGuard(impl: NoOpBackupGuard): BackupGuard
}
