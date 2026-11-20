package com.acgcompass.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.migration.DatabaseUpgradeBackup
import com.acgcompass.data.local.migration.UpgradeBackup
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 界面上展示的一份升级前备份（不暴露数据库层的类型）。 */
data class UpgradeBackupInfo(
    val id: String,
    val fromVersion: Int,
    val toVersion: Int,
    val createdAt: Long,
    val bytes: Long,
) {
    /** 导出时建议的文件名。 */
    val suggestedFileName: String get() = "hoshimi-db-$id.zip"
}

fun UpgradeBackup.toInfo(): UpgradeBackupInfo =
    UpgradeBackupInfo(dir.name, meta.fromVersion, meta.toVersion, meta.createdAt, meta.bytes)

/**
 * 安全模式与「数据安全」页用到的文件操作：导出备份、换一个干净的数据库重新开始、用升级前备份还原、重启应用。
 *
 * 「重新开始并恢复自动备份」分两步：先删掉打不开的数据库文件并留下一个标记，重启后 Room 新建空库，
 * 应用启动时（[restoreAutoBackupIfRequested]）看到标记再把那份 JSON 备份导入。升级前备份不受影响，一直保留。
 */
@Singleton
class DataSafety @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: Lazy<AcgCompassDatabase>,
    private val autoBackupManager: AutoBackupManager,
    private val dispatchers: DispatcherProvider,
) {
    private val _lastRestoreMessage = MutableStateFlow<String?>(null)

    /** 启动时按标记恢复自动备份的结果，界面显示一次后清掉。 */
    val lastRestoreMessage: StateFlow<String?> = _lastRestoreMessage.asStateFlow()

    fun consumeRestoreMessage() {
        _lastRestoreMessage.value = null
    }

    /** 全部升级前备份，最新的在前。 */
    suspend fun upgradeBackups(): List<UpgradeBackupInfo> = withContext(dispatchers.io) {
        DatabaseUpgradeBackup.list(context).map { it.toInfo() }
    }

    /** [at] 之前（含）最近的一份自动备份：升级后生成的备份可能已经是缺数据的版本。 */
    suspend fun autoBackupBefore(at: Long): AutoBackupFile? = withContext(dispatchers.io) {
        autoBackupManager.list().firstOrNull { it.createdAt <= at }
    }

    /** 把一份升级前备份（数据库文件 + meta.json）打包成 zip 写到 [target]。 */
    suspend fun exportUpgradeBackup(id: String, target: Uri): Boolean = withContext(dispatchers.io) {
        val backup = findUpgradeBackup(id) ?: return@withContext false
        runCatchingCancellable {
            val out = context.contentResolver.openOutputStream(target) ?: error("无法写入")
            ZipOutputStream(out.buffered()).use { zip ->
                backup.dir.listFiles().orEmpty().filter { it.isFile }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            true
        }.getOrDefault(false)
    }

    /** 从一份自动备份恢复（按导入规则合并，不删除现有记录）。 */
    suspend fun restoreAutoBackup(backup: AutoBackupFile): AppResult<com.acgcompass.domain.repository.ImportReport> =
        autoBackupManager.restore(backup)

    /** 把一份自动备份原样写到 [target]。 */
    suspend fun exportAutoBackup(backup: AutoBackupFile, target: Uri): Boolean = withContext(dispatchers.io) {
        runCatchingCancellable {
            val out = context.contentResolver.openOutputStream(target) ?: error("无法写入")
            out.use { stream -> backup.file.inputStream().use { it.copyTo(stream) } }
            true
        }.getOrDefault(false)
    }

    /** 用一份升级前备份替换当前数据库并重启：重启后按正常流程重新迁移。先关闭数据库连接，避免复制时被占用。 */
    suspend fun restoreUpgradeBackupAndRestart(id: String) {
        val backup = withContext(dispatchers.io) { findUpgradeBackup(id) } ?: return
        withContext(dispatchers.io) {
            runCatchingCancellable { database.get().close() }
            DatabaseUpgradeBackup.restore(context, backup)
        }
        restartApp(context)
    }

    /**
     * 删掉当前数据库文件并重启，下次启动用空库；[autoBackupName] 不为空时，启动后自动导入这份自动备份。
     */
    suspend fun freshStartAndRestart(autoBackupName: String?) {
        withContext(dispatchers.io) {
            val flag = File(context.filesDir, RESTORE_FLAG)
            if (autoBackupName != null) flag.writeText(autoBackupName) else flag.delete()
            val db = context.getDatabasePath(AcgCompassDatabase.DATABASE_NAME)
            listOf(
                db,
                File(db.path + "-wal"),
                File(db.path + "-shm"),
                File(db.path + "-journal"),
            ).forEach { it.delete() }
        }
        restartApp(context)
    }

    /** 启动时检查标记，有就导入对应的自动备份。标记先删掉，失败也不会反复尝试。 */
    suspend fun restoreAutoBackupIfRequested() {
        val name = withContext(dispatchers.io) {
            val flag = File(context.filesDir, RESTORE_FLAG)
            if (!flag.isFile) return@withContext null
            runCatchingCancellable { flag.readText().trim() }.getOrDefault("").also { flag.delete() }
        } ?: return
        val backup = autoBackupManager.list().firstOrNull { it.file.name == name }
        _lastRestoreMessage.value = when (val result = backup?.let { autoBackupManager.restore(it) }) {
            null -> "没有找到要恢复的自动备份，已用空白数据开始"
            is AppResult.Success -> "已从自动备份恢复：新增 ${result.data.added} 条，更新 ${result.data.updated} 条"
            is AppResult.Failure -> "自动备份恢复失败：${result.error.cause}"
        }
    }

    private fun findUpgradeBackup(id: String): UpgradeBackup? =
        DatabaseUpgradeBackup.list(context).firstOrNull { it.dir.name == id }

    private companion object {
        const val RESTORE_FLAG = "restore-auto-backup"
    }
}

/** 重新启动应用（结束当前进程）。 */
private fun restartApp(context: Context) {
    val component = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component ?: return
    context.startActivity(Intent.makeRestartActivityTask(component))
    exitProcess(0)
}
