package com.acgcompass.data.local.migration

/**
 * Safety-net contract that produces an internal JSON backup of business data **before** a
 * destructive-risk database upgrade or migration runs.
 *
 * [BackupGuard] separates migration orchestration from backup serialization.

 * The default [NoOpBackupGuard] performs no I/O; persistent implementations can replace it
 * through [MigrationModule] without changing callers.
 *
 * Implementations MUST:
 * - Exclude credentials, keys, and tokens from the produced backup.
 * - Write the backup to **app-internal** storage only (never world-readable, never network).
 * - Be safe to call when there is nothing to back up (return [PreMigrationBackup.empty]).
 */
interface BackupGuard {

    /**
     * Produce a pre-migration backup of all business data as a recovery safety net.
     *
     * @param fromVersion the schema version currently on disk (before migration).
     * @param toVersion the target schema version the app is upgrading to.
     * @return a descriptor of the backup that was written. Implementations that cannot or need not
     *   write a backup should return [PreMigrationBackup.empty]; failures should be surfaced by
     *   throwing so the caller ([DatabasePreMigrationBackup]) can record a recoverable error.
     */
    suspend fun createPreMigrationBackup(fromVersion: Int, toVersion: Int): PreMigrationBackup
}

/**
 * Descriptor of a backup written by a [BackupGuard]. The actual payload lives on internal storage
 * at [filePath]; this object is the lightweight handle the migration flow tracks for recovery.
 */
data class PreMigrationBackup(
    /** Absolute path to the internal backup file, or `null` when no payload was written. */
    val filePath: String?,
    /** Wall-clock time the backup was created (epoch millis). */
    val createdAtEpochMillis: Long,
    /** Schema version on disk before the migration. */
    val fromVersion: Int,
    /** Target schema version of the upgrade. */
    val toVersion: Int,
    /** Size of the written payload in bytes (0 when none). */
    val byteSize: Long,
) {
    /** True when an actual backup payload was written to disk. */
    val hasPayload: Boolean get() = filePath != null && byteSize > 0L

    companion object {
        /** A backup descriptor representing "nothing was written" (no payload). */
        fun empty(fromVersion: Int, toVersion: Int, atEpochMillis: Long): PreMigrationBackup = PreMigrationBackup(
            filePath = null,
            createdAtEpochMillis = atEpochMillis,
            fromVersion = fromVersion,
            toVersion = toVersion,
            byteSize = 0L,
        )
    }
}
