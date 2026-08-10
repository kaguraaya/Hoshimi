package com.acgcompass.data.local.migration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Unit tests for the upgrade-time backup safety net.
 *
 * Exercises the pure orchestration in [DatabasePreMigrationBackup] with in-memory fakes for the
 * version store and backup guard — no Android dependencies.
 */
class DatabasePreMigrationBackupTest : StringSpec({

    "AcgMigrations.ALL 连续覆盖 1 → CURRENT_DB_VERSION、无破坏性回退（版本号与迁移数须同步）" {
        // local-first：绝不用 fallbackToDestructiveMigration；每个相邻版本一个手写迁移，且必须覆盖到
        // @Database(version) 对应的 CURRENT_DB_VERSION。此断言守护二者同步，防止再次脱节
        // （历史上 CURRENT_DB_VERSION 停在 1，导致升级备份安全网静默失效）。
        AcgMigrations.ALL.size shouldBe DatabasePreMigrationBackup.CURRENT_DB_VERSION - 1
        AcgMigrations.ALL.first().startVersion shouldBe 1
        AcgMigrations.ALL.last().endVersion shouldBe DatabasePreMigrationBackup.CURRENT_DB_VERSION
    }

    "fresh install reports FreshInstall and creates no backup" {
        val store = FakeMigrationVersionStore(initialVersion = null)
        val guard = CountingBackupGuard()
        val helper = DatabasePreMigrationBackup(store, guard)

        helper.runIfUpgrade() shouldBe PreMigrationOutcome.FreshInstall
        guard.calls shouldBe 0
        store.recoveryState().first() shouldBe RecoveryState.None
    }

    "same version reports NoUpgrade and creates no backup" {
        val store = FakeMigrationVersionStore(initialVersion = DatabasePreMigrationBackup.CURRENT_DB_VERSION)
        val guard = CountingBackupGuard()
        val helper = DatabasePreMigrationBackup(store, guard)

        helper.runIfUpgrade() shouldBe PreMigrationOutcome.NoUpgrade
        guard.calls shouldBe 0
    }

    "downgrade is treated as NoUpgrade (no destructive fallback path)" {
        val store = FakeMigrationVersionStore(initialVersion = DatabasePreMigrationBackup.CURRENT_DB_VERSION + 5)
        val guard = CountingBackupGuard()
        val helper = DatabasePreMigrationBackup(store, guard)

        helper.runIfUpgrade() shouldBe PreMigrationOutcome.NoUpgrade
        guard.calls shouldBe 0
    }

    "genuine upgrade creates a backup and records a pending recovery" {
        // last < current => upgrade. Use current as a higher target by faking an older version.
        val store = FakeMigrationVersionStore(initialVersion = DatabasePreMigrationBackup.CURRENT_DB_VERSION - 1)
        val guard = CountingBackupGuard(path = "/data/internal/pre_migration.json", size = 128L)
        val helper = DatabasePreMigrationBackup(store, guard)

        val outcome = helper.runIfUpgrade()

        outcome.shouldBeInstanceOf<PreMigrationOutcome.BackupCreated>()
        guard.calls shouldBe 1
        val recovery = store.recoveryState().first()
        recovery.shouldBeInstanceOf<RecoveryState.RecoverableBackupAvailable>()
        recovery.backupPath shouldBe "/data/internal/pre_migration.json"
    }

    "backup failure surfaces BackupFailed without losing data" {
        val store = FakeMigrationVersionStore(initialVersion = DatabasePreMigrationBackup.CURRENT_DB_VERSION - 1)
        val boom = IllegalStateException("disk full")
        val helper = DatabasePreMigrationBackup(store, ThrowingBackupGuard(boom))

        val outcome = helper.runIfUpgrade()

        outcome.shouldBeInstanceOf<PreMigrationOutcome.BackupFailed>()
        outcome.cause shouldBe boom
        // No pending recovery recorded since the original DB is untouched at this point.
        store.recoveryState().first() shouldBe RecoveryState.None
    }

    "markMigrationSucceeded persists new version and clears recovery" {
        val store = FakeMigrationVersionStore(initialVersion = DatabasePreMigrationBackup.CURRENT_DB_VERSION - 1)
        val guard = CountingBackupGuard(path = "/p", size = 1L)
        val helper = DatabasePreMigrationBackup(store, guard)

        val outcome = helper.runIfUpgrade()
        outcome.shouldBeInstanceOf<PreMigrationOutcome.BackupCreated>()

        helper.markMigrationSucceeded()

        store.lastKnownVersion() shouldBe DatabasePreMigrationBackup.CURRENT_DB_VERSION
        store.recoveryState().first() shouldBe RecoveryState.None
    }

    "markMigrationFailed retains backup and leaves last version unchanged" {
        val previous = DatabasePreMigrationBackup.CURRENT_DB_VERSION - 1
        val store = FakeMigrationVersionStore(initialVersion = previous)
        val guard = CountingBackupGuard(path = "/data/internal/backup.json", size = 64L)
        val helper = DatabasePreMigrationBackup(store, guard)

        val outcome = helper.runIfUpgrade()
        helper.markMigrationFailed(outcome)

        // Version not advanced => next launch still sees a pending upgrade.
        store.lastKnownVersion() shouldBe previous
        val recovery = store.recoveryState().first()
        recovery.shouldBeInstanceOf<RecoveryState.RecoverableBackupAvailable>()
        recovery.backupPath shouldBe "/data/internal/backup.json"
    }
})

/** In-memory [MigrationVersionStore] for JVM tests. */
private class FakeMigrationVersionStore(initialVersion: Int?) : MigrationVersionStore {
    private var version: Int? = initialVersion
    private val recovery = MutableStateFlow<RecoveryState>(RecoveryState.None)

    override suspend fun lastKnownVersion(): Int? = version

    override suspend fun setLastKnownVersion(version: Int) {
        this.version = version
    }

    override suspend fun recordPendingRecovery(backupPath: String?) {
        recovery.value = RecoveryState.RecoverableBackupAvailable(backupPath)
    }

    override suspend fun clearPendingRecovery() {
        recovery.value = RecoveryState.None
    }

    override fun recoveryState(): Flow<RecoveryState> = recovery
}

/** [BackupGuard] that counts invocations and returns a configurable descriptor. */
private class CountingBackupGuard(
    private val path: String? = null,
    private val size: Long = 0L,
) : BackupGuard {
    var calls: Int = 0
        private set

    override suspend fun createPreMigrationBackup(fromVersion: Int, toVersion: Int): PreMigrationBackup {
        calls++
        return PreMigrationBackup(
            filePath = path,
            createdAtEpochMillis = 0L,
            fromVersion = fromVersion,
            toVersion = toVersion,
            byteSize = size,
        )
    }
}

/** [BackupGuard] that always fails, to exercise the recoverable-error path. */
private class ThrowingBackupGuard(private val error: Throwable) : BackupGuard {
    override suspend fun createPreMigrationBackup(fromVersion: Int, toVersion: Int): PreMigrationBackup = throw error
}
