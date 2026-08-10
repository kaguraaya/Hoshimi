package com.acgcompass.data.sync

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.acgcompass.data.local.dao.UserCollectionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 统一同步状态快照；所有页面读取同一份持久化状态。 */
data class SyncStatus(
    /** 上次成功同步的时间戳（epoch millis）；0 表示从未同步。 */
    val lastSyncAt: Long = 0L,
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    /** 上次同步时远端报告的收藏总数。 */
    val remoteTotal: Int = 0,
    /** 上次同步失败的可读原因；null 表示无错误。 */
    val lastError: String? = null,
    /** 当前同步来源标签（如「Bangumi」）。 */
    val currentSource: String = "Bangumi",
    /** 本地已入库的收藏数（实时来自 user_collections）。 */
    val localCollectionCount: Int = 0,
) {
    val hasSynced: Boolean get() = lastSyncAt > 0L

    /** 「最后同步时间」可读文案。 */
    fun lastSyncText(): String =
        if (lastSyncAt <= 0L) "尚未同步" else TIME_FORMATTER.format(Instant.ofEpochMilli(lastSyncAt))

    /** 上次同步结果摘要（新增/更新/跳过/失败）。 */
    fun resultSummary(): String = "新增 $added · 更新 $updated · 跳过 $skipped · 失败 $failed"

    private companion object {
        val TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}

private const val SYNC_STATUS_DATASTORE_NAME = "acg_compass_sync_status"

private val Context.syncStatusDataStore: DataStore<Preferences> by preferencesDataStore(
    name = SYNC_STATUS_DATASTORE_NAME,
)

/**
 * 统一同步状态仓库，集中持久化最后同步时间、结果、错误和来源，并结合
 * [UserCollectionDao] 实时本地收藏数，对外暴露单一 [SyncStatus] 流。
 *
 * 写入方：[BangumiSyncManager] 每次同步后调用 [recordSuccess] / [recordFailure]。
 * 读取方：首页同步提醒、我的页、时光机、口味画像等统一消费 [status]，状态全局一致。
 */
@Singleton
class SyncStatusRepository @Inject constructor(
    @ApplicationContext context: Context,
    userCollectionDao: UserCollectionDao,
) {
    private val ds = context.syncStatusDataStore

    /** 统一同步状态流：DataStore 持久化字段 + 实时本地收藏计数。 */
    val status: Flow<SyncStatus> = combine(
        ds.data,
        userCollectionDao.observeAll(),
    ) { prefs, collections ->
        SyncStatus(
            lastSyncAt = prefs[Keys.LAST_SYNC_AT] ?: 0L,
            added = prefs[Keys.ADDED] ?: 0,
            updated = prefs[Keys.UPDATED] ?: 0,
            skipped = prefs[Keys.SKIPPED] ?: 0,
            failed = prefs[Keys.FAILED] ?: 0,
            remoteTotal = prefs[Keys.REMOTE_TOTAL] ?: 0,
            lastError = prefs[Keys.LAST_ERROR]?.takeIf { it.isNotBlank() },
            currentSource = prefs[Keys.CURRENT_SOURCE] ?: "Bangumi",
            localCollectionCount = collections.size,
        )
    }

    /** 记录一次成功同步并清除上次错误。 */
    suspend fun recordSuccess(report: SyncReport, source: String = "Bangumi") {
        ds.edit { p ->
            p[Keys.LAST_SYNC_AT] = System.currentTimeMillis()
            p[Keys.ADDED] = report.added
            p[Keys.UPDATED] = report.updated
            p[Keys.SKIPPED] = report.skipped
            p[Keys.FAILED] = report.failed
            p[Keys.REMOTE_TOTAL] = report.total
            p[Keys.CURRENT_SOURCE] = source
            p.remove(Keys.LAST_ERROR)
        }
    }

    /** 记录一次同步失败的可读原因，不更新最后同步时间。 */
    suspend fun recordFailure(message: String, source: String = "Bangumi") {
        ds.edit { p ->
            p[Keys.LAST_ERROR] = message
            p[Keys.CURRENT_SOURCE] = source
        }
    }

    private object Keys {
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val ADDED = intPreferencesKey("last_added")
        val UPDATED = intPreferencesKey("last_updated")
        val SKIPPED = intPreferencesKey("last_skipped")
        val FAILED = intPreferencesKey("last_failed")
        val REMOTE_TOTAL = intPreferencesKey("last_remote_total")
        val LAST_ERROR = stringPreferencesKey("last_error")
        val CURRENT_SOURCE = stringPreferencesKey("current_source")
    }
}
