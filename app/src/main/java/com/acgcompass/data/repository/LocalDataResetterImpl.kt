package com.acgcompass.data.repository

import android.content.Context
import androidx.work.WorkManager
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.domain.repository.LocalDataResetter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDataResetterImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AcgCompassDatabase,
    private val credentialStore: CredentialStore,
    private val settingsDataStore: SettingsDataStore,
) : LocalDataResetter {
    override suspend fun reset() {
        WorkManager.getInstance(context).cancelAllWork()
        credentialStore.clearAll()
        settingsDataStore.clearAll()
        database.clearAllTables()
        context.cacheDir.deleteRecursively()
        context.codeCacheDir.deleteRecursively()
        context.filesDir.deleteRecursively()
        context.noBackupFilesDir.deleteRecursively()
    }
}
