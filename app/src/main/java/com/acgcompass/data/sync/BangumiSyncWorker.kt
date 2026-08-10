package com.acgcompass.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.acgcompass.core.common.AppResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 周期性后台同步 Worker。由 [SyncScheduler] 按用户设置的间隔调度，调用
 * [BangumiSyncManager.syncCollections] 拉取最新 Bangumi 收藏到本地。
 *
 * 韧性：
 * - 未配置凭据 / 非官方 API 未确认时同步返回 Failure → 视为「已处理」（[Result.success]），不重试刷屏。
 * - 网络 / 超时类失败 → [Result.retry]，由 WorkManager 退避重试。
 * - 任何异常都被兜底，绝不让进程崩溃。
 */
@HiltWorker
class BangumiSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: BangumiSyncManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching {
        // 未配置时不重试（用户尚未登录 Bangumi），直接成功结束。
        if (!syncManager.isConfigured()) return Result.success()
        when (syncManager.syncCollections()) {
            is AppResult.Success -> Result.success()
            // 失败大多为网络/限流/鉴权过期；交给 WorkManager 退避重试。
            is AppResult.Failure -> Result.retry()
        }
    }.getOrElse { Result.retry() }

    companion object {
        const val UNIQUE_WORK_NAME = "bangumi_auto_sync"
    }
}
