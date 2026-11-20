package com.acgcompass.data.backup

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.domain.repository.BackupRepository
import com.acgcompass.domain.repository.ImportReport
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 一份自动备份文件。 */
data class AutoBackupFile(val file: File, val createdAt: Long, val bytes: Long)

/**
 * 自动备份：用和「导出备份」相同的 JSON 格式（和数据库结构无关，跨版本可以导入）写到应用私有目录
 * `files/auto-backups/`，保留最近 [KEEP] 份，不含任何凭据。
 *
 * 为了确保真的在跑，而不是只登记了一个周期任务：
 * - 写入先写临时文件，读回来解析成功后才改名为正式文件，失败的不留下；
 * - 每次结果（成功时间 / 失败原因）记进设置，「数据安全」页可以看到；
 * - 除了 WorkManager 每周任务，应用启动时也检查一次，超过 [INTERVAL_DAYS] 天没成功就立即补做。
 */
@Singleton
class AutoBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val settingsDataStore: SettingsDataStore,
    private val dispatchers: DispatcherProvider,
) {
    private val lock = Mutex()
    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }

    suspend fun backupNow(now: Long = System.currentTimeMillis()): AppResult<AutoBackupFile> =
        withContext(dispatchers.io) {
            lock.withLock {
                val result = writeBackup(now)
                when (result) {
                    is AppResult.Success -> settingsDataStore.recordAutoBackupSuccess(now)
                    is AppResult.Failure -> settingsDataStore.recordAutoBackupFailure(result.error.cause)
                }
                result
            }
        }

    /** 开着自动备份、而且超过 [INTERVAL_DAYS] 天没成功过时补做一次。 */
    suspend fun runIfDue(now: Long = System.currentTimeMillis()) {
        if (!settingsDataStore.autoBackupEnabled.first()) return
        val (lastSuccess, _) = settingsDataStore.autoBackupStatus.first()
        if (now - lastSuccess >= INTERVAL_DAYS * MILLIS_PER_DAY) backupNow(now)
    }

    /** 全部自动备份，最新的在前。 */
    fun list(): List<AutoBackupFile> =
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(".json") }
            .orEmpty()
            .map { AutoBackupFile(it, it.lastModified(), it.length()) }
            .sortedByDescending { it.createdAt }

    /** 从一份自动备份恢复（与导入备份相同，按现有规则合并）。 */
    suspend fun restore(backup: AutoBackupFile): AppResult<ImportReport> = withContext(dispatchers.io) {
        if (!backup.file.canRead()) {
            return@withContext AppResult.Failure(AppError.FieldMissing(cause = "备份文件读不出来"))
        }
        backup.file.inputStream().buffered().use { backupRepository.importBackupFrom(it) }
    }

    private suspend fun writeBackup(now: Long): AppResult<AutoBackupFile> {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
        val target = File(dir, "$PREFIX$stamp.json")
        val temp = File(dir, "$PREFIX$stamp.json.tmp")
        // 直接写进临时文件，不在内存里拼出整段 JSON。
        val exported = temp.outputStream().buffered().use { backupRepository.exportBackupTo(it) }
        if (exported is AppResult.Failure) {
            temp.delete()
            return exported
        }
        return runCatchingCancellable {
            // 读回来能解析才算成功：防止写了一半或格式错误的文件冒充备份。
            temp.inputStream().buffered().use { BackupSerializer.deserializeFrom(it) }
            if (!temp.renameTo(target)) error("无法保存备份文件")
            prune()
            AutoBackupFile(target, target.lastModified(), target.length())
        }.fold(
            onSuccess = { AppResult.Success(it) },
            onFailure = { error ->
                temp.delete()
                AppResult.Failure(AppError.Server(cause = "自动备份失败：${error.message ?: "写入出错"}"))
            },
        )
    }

    private fun prune() {
        list().drop(KEEP).forEach { it.file.delete() }
    }

    companion object {
        const val DIR = "auto-backups"
        const val INTERVAL_DAYS = 7L
        private const val PREFIX = "hoshimi-auto-"
        private const val KEEP = 3
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}

/** 按开关登记 / 取消每周自动备份任务（电量不低时执行，不需要联网）。 */
@Singleton
class AutoBackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun apply(enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(AutoBackupWorker.UNIQUE_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(AutoBackupManager.INTERVAL_DAYS, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        workManager.enqueueUniquePeriodicWork(
            AutoBackupWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

@HiltWorker
class AutoBackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val manager: AutoBackupManager,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when (manager.backupNow()) {
        is AppResult.Success -> Result.success()
        is AppResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "auto_backup_weekly"
        private const val MAX_ATTEMPTS = 3
    }
}
