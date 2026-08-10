package com.acgcompass.data.local.migration

import javax.inject.Inject

/**
 * Stub [BackupGuard] used until the full backup serializer lands in .
 *
 * It performs no I/O and always reports that no payload was written ([PreMigrationBackup.empty]),
 * which keeps the migration mechanism fully wired and testable today without depending on the
 * not-yet-implemented serializer.
 *
 * ## Integration point
 * Replace this binding with the real serializer-backed implementation:
 * 1. Implement [BackupGuard] in the backup module, serializing business data (works/backlog/
 *    ratings/reviews/tags/import batches/snapshots/settings — **excluding credentials**)
 *    to an app-internal JSON file and returning a populated [PreMigrationBackup].
 * 2. In [MigrationModule], swap `bindBackupGuard` to bind that implementation instead of this one.
 * No other call site changes — [DatabasePreMigrationBackup] already consumes the [BackupGuard] seam.
 */
class NoOpBackupGuard @Inject constructor() : BackupGuard {

    override suspend fun createPreMigrationBackup(fromVersion: Int, toVersion: Int): PreMigrationBackup =
        PreMigrationBackup.empty(
            fromVersion = fromVersion,
            toVersion = toVersion,
            atEpochMillis = System.currentTimeMillis(),
        )
}
