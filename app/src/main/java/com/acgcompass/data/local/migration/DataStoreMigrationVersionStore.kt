package com.acgcompass.data.local.migration

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Dedicated DataStore file for migration bookkeeping. Separate from the settings DataStore so the
 * two never contend on the same file (Jetpack DataStore requires one instance per file).
 */
private const val MIGRATION_DATASTORE_NAME = "acg_compass_migration"

private val Context.migrationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = MIGRATION_DATASTORE_NAME,
)

/**
 * Jetpack DataStore-backed [MigrationVersionStore] .
 *
 * Persists only the last successfully opened schema version and a small recovery flag/path; never
 * any business data or credentials ().
 */
@Singleton
class DataStoreMigrationVersionStore @Inject constructor(
    @ApplicationContext context: Context,
) : MigrationVersionStore {

    private val dataStore: DataStore<Preferences> = context.migrationDataStore

    override suspend fun lastKnownVersion(): Int? = dataStore.data.first()[Keys.LAST_KNOWN_DB_VERSION]

    override suspend fun setLastKnownVersion(version: Int) {
        dataStore.edit { it[Keys.LAST_KNOWN_DB_VERSION] = version }
    }

    override suspend fun recordPendingRecovery(backupPath: String?) {
        dataStore.edit { prefs ->
            prefs[Keys.RECOVERY_PENDING] = true
            if (backupPath != null) {
                prefs[Keys.RECOVERY_BACKUP_PATH] = backupPath
            } else {
                prefs.remove(Keys.RECOVERY_BACKUP_PATH)
            }
        }
    }

    override suspend fun clearPendingRecovery() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.RECOVERY_PENDING)
            prefs.remove(Keys.RECOVERY_BACKUP_PATH)
        }
    }

    override fun recoveryState(): Flow<RecoveryState> = dataStore.data.map { prefs ->
        if (prefs[Keys.RECOVERY_PENDING] == true) {
            RecoveryState.RecoverableBackupAvailable(prefs[Keys.RECOVERY_BACKUP_PATH])
        } else {
            RecoveryState.None
        }
    }

    private object Keys {
        val LAST_KNOWN_DB_VERSION = intPreferencesKey("last_known_db_version")
        val RECOVERY_PENDING = booleanPreferencesKey("migration_recovery_pending")
        val RECOVERY_BACKUP_PATH = stringPreferencesKey("migration_recovery_backup_path")
    }
}
