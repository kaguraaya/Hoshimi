package com.acgcompass.data.local.migration

import android.content.Context
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.local.AcgCompassDatabase
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 数据库在这次启动时的状态。 */
sealed interface DatabaseHealth {
    data object Checking : DatabaseHealth

    data object Ok : DatabaseHealth

    /** 升级后核心表的行数明显变少，可能丢了数据；[backup] 是升级前的备份。 */
    data class DataMayBeMissing(val details: String, val backup: UpgradeBackup) : DatabaseHealth

    /** 数据库打不开（迁移失败）；[backup] 是升级前的备份（如果有）。 */
    data class Failed(val message: String, val backup: UpgradeBackup?) : DatabaseHealth
}

/**
 * 启动时打开数据库并自检：
 * - 打开失败（迁移抛异常）→ [DatabaseHealth.Failed]，界面进入安全模式，数据原样留在升级前备份里；
 * - 刚升级过（有待自检的升级前备份）→ 对比核心表行数，某张表比升级前少了 [LOSS_RATIO] 以上（且原来至少
 *   [MIN_ROWS] 行）→ [DatabaseHealth.DataMayBeMissing]，提示可以恢复；否则清掉待自检标记。
 */
@Singleton
class DatabaseHealthMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: Lazy<AcgCompassDatabase>,
) {
    private val _state = MutableStateFlow<DatabaseHealth>(DatabaseHealth.Checking)
    val state: StateFlow<DatabaseHealth> = _state.asStateFlow()

    /** 这次启动是否刚完成一次数据库结构升级、并且自检通过。 */
    @Volatile
    var upgradeVerified: Boolean = false
        private set

    suspend fun check(): DatabaseHealth = withContext(Dispatchers.IO) {
        // 先构建数据库实例：升级前备份在构建时（Room 打开文件之前）生成，之后才能读到待自检标记。
        val instance = runCatchingCancellable { database.get() }
        val pending = runCatchingCancellable { DatabaseUpgradeBackup.pendingVerification(context) }.getOrNull()
        val result = runCatchingCancellable {
            val db = instance.getOrThrow().openHelper.writableDatabase
            if (pending == null) return@runCatchingCancellable DatabaseHealth.Ok
            val after = DatabaseUpgradeBackup.CORE_TABLES.associateWith { table ->
                runCatchingCancellable {
                    db.query("SELECT COUNT(*) FROM `$table`").use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                }.getOrDefault(0L)
            }
            val losses = pending.meta.counts.mapNotNull { (table, before) ->
                val now = after[table] ?: 0L
                if (before >= MIN_ROWS && now < before * (1 - LOSS_RATIO)) "$table：$before → $now" else null
            }
            if (losses.isEmpty()) {
                DatabaseUpgradeBackup.clearPendingVerification(context)
                upgradeVerified = true
                DatabaseHealth.Ok
            } else {
                DatabaseHealth.DataMayBeMissing(losses.joinToString("；"), pending)
            }
        }.getOrElse { error ->
            DatabaseHealth.Failed(error.message ?: error::class.java.simpleName, pending)
        }
        _state.value = result
        result
    }

    /** 用户确认数据没问题后，清掉「可能丢数据」的提示。 */
    fun dismissWarning() {
        DatabaseUpgradeBackup.clearPendingVerification(context)
        _state.value = DatabaseHealth.Ok
    }

    private companion object {
        const val LOSS_RATIO = 0.1
        const val MIN_ROWS = 10L
    }
}
