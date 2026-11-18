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
 * - 未配置凭据时直接成功结束。
 * - 网络、超时、服务端错误、限流（[isTransient]）→ [Result.retry]，由 WorkManager 退避重试，最多 [MAX_ATTEMPTS] 次，
 *   之后等下一个周期。
 * - 鉴权失效、非官方 API 未确认等再试也不会成功的失败 → [Result.success]，结果已写进同步状态，不反复重试。
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
        when (val result = syncManager.syncCollections()) {
            is AppResult.Success -> Result.success()
            is AppResult.Failure ->
                if (result.error.isTransient() && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }.getOrElse { if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success() }

    companion object {
        const val UNIQUE_WORK_NAME = "bangumi_auto_sync"

        /** 同一个周期内最多重试的次数。 */
        private const val MAX_ATTEMPTS = 3
    }
}
