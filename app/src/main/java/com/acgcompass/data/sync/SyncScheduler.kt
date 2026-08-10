package com.acgcompass.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动同步调度器。根据用户在设置中选择的间隔（分钟）注册 / 取消周期性
 * [BangumiSyncWorker]。
 *
 * - `intervalMinutes <= 0`：取消已注册的周期任务（关闭自动同步）。
 * - 否则按间隔注册唯一周期任务（[ExistingPeriodicWorkPolicy.UPDATE]，间隔变化即时生效）。
 *   WorkManager 周期下限为 15 分钟，小于该值会被钳制。
 * - 仅在有网络时执行（[NetworkType.CONNECTED]）。
 */
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** 根据间隔（分钟）应用调度；0 或负数表示关闭。 */
    fun apply(intervalMinutes: Int) {
        if (intervalMinutes <= 0) {
            workManager.cancelUniqueWork(BangumiSyncWorker.UNIQUE_WORK_NAME)
            return
        }
        val minutes = intervalMinutes.toLong().coerceAtLeast(MIN_PERIODIC_MINUTES)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<BangumiSyncWorker>(minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniquePeriodicWork(
            BangumiSyncWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private companion object {
        /** WorkManager 周期任务最小间隔（分钟）。 */
        const val MIN_PERIODIC_MINUTES = 15L
    }
}
