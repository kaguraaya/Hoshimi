package com.acgcompass.data.local.migration

import com.acgcompass.data.local.AcgCompassDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Upgrade-time safety net that produces an internal JSON backup **before** a destructive-risk Room
 * migration runs, and — if the migration fails — retains the original backup and surfaces a
 * recoverable flag instead of losing data.
 *
 * ## Mechanism
 * Room performs the actual schema migration when the database is first opened. We cannot hook
 * "inside" that open from a DI provider, so this helper brackets it:
 *
 * 1. **Detect** a version change by comparing the [MigrationVersionStore]'s recorded
 *    `lastKnownVersion` against the current [AcgCompassDatabase] `@Database(version)`
 *    ([CURRENT_DB_VERSION]).
 * 2. **Back up** business data via the [BackupGuard] seam *before* opening the database for an
 *    upgrade, and mark a pending-recovery flag.
 * 3. The caller then opens the database (triggering Room migration). On success it calls
 *    [markMigrationSucceeded]; on failure it calls [markMigrationFailed] which keeps the backup and
 *    leaves the recovery flag set so the UI can prompt the user to restore .
 *
 * ## Integration point
 * Wire this into application startup (e.g. an initializer or `Application.onCreate`):
 *
 * ```kotlin
 * val outcome = preMigrationBackup.runIfUpgrade()      // before touching the DB
 * try {
 *     db.openHelper.writableDatabase                   // forces Room migration
 *     preMigrationBackup.markMigrationSucceeded()
 * } catch (t: Throwable) {
 *     preMigrationBackup.markMigrationFailed(outcome)  // retain backup + recovery flag
 *     throw t
 * }
 * ```
 *
 * The default [NoOpBackupGuard] performs no I/O and can be replaced through dependency injection.
 * Observe [recoveryState] from the UI to surface the recovery prompt.
 */
@Singleton
class DatabasePreMigrationBackup @Inject constructor(
    private val versionStore: MigrationVersionStore,
    private val backupGuard: BackupGuard,
) {

    /** Stream of recovery state for the UI to surface a restore prompt . */
    val recoveryState: Flow<RecoveryState> = versionStore.recoveryState()

    /**
     * Inspect the recorded schema version and, when this is an upgrade, create a pre-migration
     * backup before the database is opened. Safe to call on every startup.
     *
     * @return the [PreMigrationOutcome] describing what happened.
     */
    suspend fun runIfUpgrade(): PreMigrationOutcome {
        val last = versionStore.lastKnownVersion()
        return when {
            last == null -> {
                // Fresh install: nothing to migrate. Record the baseline once the DB opens.
                PreMigrationOutcome.FreshInstall
            }

            last == CURRENT_DB_VERSION -> PreMigrationOutcome.NoUpgrade

            last > CURRENT_DB_VERSION -> {
                // Downgrade is not expected; treat as no-op upgrade work. The Room builder has no
                // destructive fallback, so a real downgrade would surface its own error on open.
                PreMigrationOutcome.NoUpgrade
            }

            else -> {
                // Genuine upgrade (last < current): produce the safety-net backup first.
                try {
                    val backup = backupGuard.createPreMigrationBackup(
                        fromVersion = last,
                        toVersion = CURRENT_DB_VERSION,
                    )
                    versionStore.recordPendingRecovery(backup.filePath)
                    PreMigrationOutcome.BackupCreated(backup)
                } catch (t: Throwable) {
                    // Could not create the backup. Surface a recoverable error; the caller decides
                    // whether to proceed. The original database file is untouched at this point.
                    PreMigrationOutcome.BackupFailed(t)
                }
            }
        }
    }

    /**
     * Record that the database opened/migrated successfully: persist the new schema version and
     * clear any pending recovery flag/backup.
     */
    suspend fun markMigrationSucceeded() {
        versionStore.setLastKnownVersion(CURRENT_DB_VERSION)
        versionStore.clearPendingRecovery()
    }

    /**
     * Record that migration failed. The pre-migration backup is **retained** and the recovery flag
     * stays set so the UI can offer to restore the original data .
     *
     * The recorded `lastKnownVersion` is intentionally left unchanged so the next startup still sees
     * this as a pending upgrade.
     *
     * @param outcome the outcome returned by [runIfUpgrade], used to keep the backup path on record.
     */
    suspend fun markMigrationFailed(outcome: PreMigrationOutcome) {
        val backupPath = (outcome as? PreMigrationOutcome.BackupCreated)?.backup?.filePath
        versionStore.recordPendingRecovery(backupPath)
    }

    companion object {
        /**
         * The current schema version, mirroring `@Database(version = …)` on [AcgCompassDatabase].
         * Bump this in lockstep with the database version when adding a migration to [AcgMigrations].
         * NOTE: must equal `AcgCompassDatabase` `@Database(version)`; `DatabasePreMigrationBackupTest`
         * guards this against drift (previously stuck at 1 → upgrade backup net silently disabled).
         */
        const val CURRENT_DB_VERSION: Int = 21
    }
}

/**
 * Result of [DatabasePreMigrationBackup.runIfUpgrade].
 */
sealed interface PreMigrationOutcome {
    /** Fresh install — no prior version recorded, nothing to back up. */
    data object FreshInstall : PreMigrationOutcome

    /** No version change since last successful open. */
    data object NoUpgrade : PreMigrationOutcome

    /** An upgrade was detected and a safety-net backup was created. */
    data class BackupCreated(val backup: PreMigrationBackup) : PreMigrationOutcome

    /** An upgrade was detected but the backup could not be created; carries the failure cause. */
    data class BackupFailed(val cause: Throwable) : PreMigrationOutcome
}
