package com.acgcompass.data.tasks

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.backup.AutoBackupWorker
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.RatingBackfillDao
import com.acgcompass.data.local.dao.TaskRunDao
import com.acgcompass.data.local.entity.TaskRunEntity
import com.acgcompass.data.sync.BangumiOutbox
import com.acgcompass.data.sync.BangumiOutboxScheduler
import com.acgcompass.data.sync.BangumiOutboxWorker
import com.acgcompass.data.sync.BangumiSyncWorker
import com.acgcompass.data.sync.CacheFillWorker
import com.acgcompass.data.sync.RatingBackfillWorker
import com.acgcompass.data.sync.SyncStatusRepository
import com.acgcompass.data.sync.WorkFeatureEnrichmentScheduler
import com.acgcompass.data.sync.WorkFeatureEnrichmentWorker
import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.model.BackgroundTaskStatus
import com.acgcompass.domain.repository.BackgroundTaskRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 汇总后台任务状况：运行记录表、同步状态、自动备份结果、各设置开关，以及 WorkManager 里的运行中状态和下次计划时间。
 */
@Singleton
@Suppress("LongParameterList") // Hilt 注入
class BackgroundTaskRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRunDao: TaskRunDao,
    private val syncStatusRepository: SyncStatusRepository,
    private val settings: SettingsDataStore,
    private val outbox: BangumiOutbox,
    private val outboxScheduler: BangumiOutboxScheduler,
    private val enrichmentScheduler: WorkFeatureEnrichmentScheduler,
    private val backfillDao: RatingBackfillDao,
    private val cacheRepository: com.acgcompass.domain.repository.CacheRepository,
) : BackgroundTaskRepository {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun observeStatuses(): Flow<List<BackgroundTaskStatus>> {
        val switches = combine(
            settings.autoSyncIntervalMinutes,
            settings.cacheBackgroundFillEnabled,
            settings.scoreDiffAutoBackfill,
            settings.autoBackupEnabled,
        ) { syncMinutes, cacheFill, backfill, backup ->
            TaskSwitches(syncMinutes > 0, cacheFill, backfill, backup)
        }
        val schedules = combine(WORK_NAMES.map { (kind, names) -> scheduleOf(kind, names) }) { it.toMap() }
        val records =
            combine(taskRunDao.observeAll(), cacheRepository.observeFillRuns()) { runs, cache -> runs to cache }
        return combine(
            records,
            syncStatusRepository.status,
            settings.autoBackupStatus,
            switches,
            schedules,
        ) { (runs, cacheRuns), sync, backup, sw, sched ->
            TaskInputs(
                runs = runs.associateBy { it.taskKey },
                syncLastSuccessAt = sync.lastSyncAt.takeIf { it > 0L },
                syncLastError = sync.lastError,
                backupLastSuccessAt = backup.first.takeIf { it > 0L },
                backupLastError = backup.second,
                switches = sw,
                schedules = sched,
                cacheRuns = cacheRuns,
            )
        }.map { inputs ->
            buildBackgroundTaskStatuses(
                inputs,
                outboxPending = runCatchingCancellable { outbox.pendingCount() }.getOrDefault(0),
                backfillFailing = runCatchingCancellable { backfillDao.countFailing() }.getOrDefault(0),
                backfillRetryAt = runCatchingCancellable { backfillDao.earliestRetryAt() }.getOrNull(),
            )
        }
    }

    private fun scheduleOf(
        kind: BackgroundTaskKind,
        names: List<String>,
    ): Flow<Pair<BackgroundTaskKind, WorkSchedule>> {
        val flows = names.map { workManager.getWorkInfosForUniqueWorkFlow(it) }
        return combine(flows) { lists ->
            val infos = lists.flatMap { it }
            val running = infos.any { it.state == WorkInfo.State.RUNNING }
            val next = infos.filter { it.state == WorkInfo.State.ENQUEUED }
                .map { it.nextScheduleTimeMillis }
                .filter { it in 1 until Long.MAX_VALUE }
                .minOrNull()
            kind to WorkSchedule(running, next)
        }
    }

    override suspend fun retry(kind: BackgroundTaskKind): Boolean {
        val sw = TaskSwitches(
            sync = true,
            cacheFill = settings.cacheBackgroundFillEnabled.first(),
            backfill = settings.scoreDiffAutoBackfill.first(),
            backup = settings.autoBackupEnabled.first(),
        )
        return when (kind) {
            BackgroundTaskKind.SYNC -> enqueueNow<BangumiSyncWorker>("${BangumiSyncWorker.UNIQUE_WORK_NAME}_manual")
            BackgroundTaskKind.OUTBOX -> true.also { outboxScheduler.enqueue() }
            BackgroundTaskKind.FEATURE_ENRICHMENT -> true.also { enrichmentScheduler.enqueue() }
            BackgroundTaskKind.RATING_BACKFILL -> sw.backfill && run {
                backfillDao.retryFailedNow(System.currentTimeMillis())
                enqueueNow<RatingBackfillWorker>(RatingBackfillWorker.UNIQUE_NOW_NAME)
            }
            BackgroundTaskKind.AUTO_BACKUP ->
                sw.backup &&
                    enqueueNow<AutoBackupWorker>("${AutoBackupWorker.UNIQUE_WORK_NAME}_manual", network = false)
            // 公共缓存在「缓存管理」页也不提供立即补充（受各数据源限流约束），这里同样不提供。
            BackgroundTaskKind.CACHE_FILL, BackgroundTaskKind.POOL_EXPAND,
            BackgroundTaskKind.DETAIL_EXTRAS, BackgroundTaskKind.DETAIL_RATINGS,
            -> false
        }
    }

    private inline fun <reified W : ListenableWorker> enqueueNow(name: String, network: Boolean = true): Boolean {
        val builder = OneTimeWorkRequest.Builder(W::class.java)
        if (network) {
            builder.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        }
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, builder.build())
        return true
    }

    private companion object {
        val WORK_NAMES: List<Pair<BackgroundTaskKind, List<String>>> = listOf(
            BackgroundTaskKind.SYNC to listOf(
                BangumiSyncWorker.UNIQUE_WORK_NAME,
                "${BangumiSyncWorker.UNIQUE_WORK_NAME}_manual",
            ),
            BackgroundTaskKind.OUTBOX to listOf(BangumiOutboxWorker.UNIQUE_WORK_NAME),
            BackgroundTaskKind.FEATURE_ENRICHMENT to listOf(WorkFeatureEnrichmentWorker.UNIQUE_WORK_NAME),
            BackgroundTaskKind.RATING_BACKFILL to listOf(
                RatingBackfillWorker.UNIQUE_WORK_NAME,
                RatingBackfillWorker.UNIQUE_NOW_NAME,
            ),
            BackgroundTaskKind.CACHE_FILL to listOf(CacheFillWorker.UNIQUE_WORK_NAME),
            BackgroundTaskKind.AUTO_BACKUP to listOf(
                AutoBackupWorker.UNIQUE_WORK_NAME,
                "${AutoBackupWorker.UNIQUE_WORK_NAME}_manual",
            ),
        )
    }
}

/** 设置里的开关：关掉的任务不会按计划跑。 */
internal data class TaskSwitches(val sync: Boolean, val cacheFill: Boolean, val backfill: Boolean, val backup: Boolean)

/** WorkManager 里的状态：是否正在跑、下一次计划时间。 */
internal data class WorkSchedule(val running: Boolean, val nextRunAt: Long?)

internal data class TaskInputs(
    val runs: Map<String, TaskRunEntity>,
    val syncLastSuccessAt: Long?,
    val syncLastError: String?,
    val backupLastSuccessAt: Long?,
    val backupLastError: String?,
    val switches: TaskSwitches,
    val schedules: Map<BackgroundTaskKind, WorkSchedule>,
    val cacheRuns: List<com.acgcompass.domain.cache.CacheFillRun> = emptyList(),
)

/** 把各处的记录合成页面上的一行行状况（纯函数，便于测试）。 */
internal fun buildBackgroundTaskStatuses(
    inputs: TaskInputs,
    outboxPending: Int,
    backfillFailing: Int,
    backfillRetryAt: Long?,
): List<BackgroundTaskStatus> = BackgroundTaskKind.entries.map { kind ->
    val run = inputs.runs[kind.key]
    val schedule = inputs.schedules[kind]
    val base = BackgroundTaskStatus(
        kind = kind,
        running = (run?.running == true) || (schedule?.running == true),
        lastStartedAt = run?.lastStartedAt,
        lastSuccessAt = run?.lastSuccessAt,
        lastFailureAt = run?.lastFailureAt,
        lastError = run?.lastError,
        lastSummary = run?.lastSummary,
        nextRunAt = schedule?.nextRunAt,
    )
    when (kind) {
        // 同步：成功时间和失败原因取同步状态（首页、我的页显示的同一份），同步成功会清掉失败原因。
        BackgroundTaskKind.SYNC -> base.copy(
            lastSuccessAt = inputs.syncLastSuccessAt ?: base.lastSuccessAt,
            lastError = inputs.syncLastError,
            errorCurrent = inputs.syncLastError != null,
            disabled = !inputs.switches.sync,
        )
        BackgroundTaskKind.OUTBOX -> base.copy(
            pendingNote = outboxPending.takeIf { it > 0 }?.let { "还有 $it 条修改没推到 Bangumi" },
        )
        BackgroundTaskKind.RATING_BACKFILL -> base.copy(
            pendingNote = backfillFailing.takeIf { it > 0 }?.let { "$it 部上次没查成，会自动再试" },
            nextRunAt = listOfNotNull(base.nextRunAt, backfillRetryAt.takeIf { backfillFailing > 0 }).minOrNull(),
            disabled = !inputs.switches.backfill,
            retryable = inputs.switches.backfill,
        )
        BackgroundTaskKind.CACHE_FILL -> cacheFillStatus(base, inputs)
        // 自动备份：取「数据安全」页显示的同一份结果，成功会清掉失败原因。
        BackgroundTaskKind.AUTO_BACKUP -> base.copy(
            lastSuccessAt = inputs.backupLastSuccessAt,
            lastError = inputs.backupLastError,
            lastFailureAt = base.lastFailureAt.takeIf { inputs.backupLastError != null },
            errorCurrent = inputs.backupLastError != null,
            lastSummary = null,
            disabled = !inputs.switches.backup,
            retryable = inputs.switches.backup,
        )
        BackgroundTaskKind.POOL_EXPAND, BackgroundTaskKind.DETAIL_EXTRAS, BackgroundTaskKind.DETAIL_RATINGS,
        -> base.copy(retryable = false)
        BackgroundTaskKind.FEATURE_ENRICHMENT -> base
    }
}

/** 补充公共缓存：全部取「缓存管理」页用的同一份各来源记录，不另记。 */
private fun cacheFillStatus(base: BackgroundTaskStatus, inputs: TaskInputs): BackgroundTaskStatus {
    val runs = inputs.cacheRuns
    val lastFailed = runs.filter { it.result == "FAILED" }.maxByOrNull { it.lastRunAt ?: 0L }
    return base.copy(
        running = runs.any { it.result == "RUNNING" } || base.running,
        lastStartedAt = runs.mapNotNull { it.lastRunAt }.maxOrNull(),
        lastSuccessAt = runs.mapNotNull { it.lastSuccessAt }.maxOrNull(),
        lastFailureAt = lastFailed?.lastRunAt,
        lastError = lastFailed?.failureReason ?: lastFailed?.let { "本次失败，可稍后继续" },
        errorCurrent = lastFailed != null,
        lastSummary = null,
        cacheRuns = runs,
        disabled = !inputs.switches.cacheFill,
        retryable = false,
    )
}
