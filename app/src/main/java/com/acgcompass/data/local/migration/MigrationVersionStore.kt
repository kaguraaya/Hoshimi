package com.acgcompass.data.local.migration

import kotlinx.coroutines.flow.Flow

/**
 * Persistent record of the database schema version the app last successfully opened, plus a flag
 * for a pending migration-recovery backup .
 *
 * Kept behind an interface so the orchestration in [DatabasePreMigrationBackup] is pure and
 * JVM-unit-testable with an in-memory fake; the production implementation
 * ([DataStoreMigrationVersionStore]) is backed by Jetpack DataStore.
 *
 * This store holds **only** a small integer version and a non-sensitive recovery flag/path — never
 * any business data or credentials.
 */
interface MigrationVersionStore {

    /**
     * The schema version recorded after the last successful database open, or `null` on a fresh
     * install (nothing recorded yet).
     */
    suspend fun lastKnownVersion(): Int?

    /** Record [version] as the last successfully opened schema version. */
    suspend fun setLastKnownVersion(version: Int)

    /**
     * Mark that a migration is being attempted and a recovery backup at [backupPath] (may be `null`
     * if no payload was produced) is being held as a safety net.
     */
    suspend fun recordPendingRecovery(backupPath: String?)

    /** Clear any pending recovery state after a successful migration. */
    suspend fun clearPendingRecovery()

    /** Observe the current [RecoveryState] so the UI can surface a recovery prompt . */
    fun recoveryState(): Flow<RecoveryState>
}

/**
 * Whether a previous migration left a recoverable backup the user should be prompted about.
 */
sealed interface RecoveryState {
    /** No pending recovery — normal state. */
    data object None : RecoveryState

    /**
     * A migration failed (or is in-flight and was interrupted); the original pre-migration backup is
     * retained for the user to restore .
     *
     * @param backupPath internal path of the retained backup, or `null` if no payload was written
     *   (e.g. stub guard) — in that case the original on-disk database remains the source of truth.
     */
    data class RecoverableBackupAvailable(val backupPath: String?) : RecoveryState
}
