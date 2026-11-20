package com.acgcompass.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 待推送队列：本地改了、还没推到 Bangumi 的收藏（`updatedAt > syncedAt`）逐条推送。
 * 不另建表，「未同步」由时间戳判断，所以任何写入路径（详情页保存失败、吃灰馆归档失败、回收站恢复、
 * 账号合并）留下的修改都会被推送。
 *
 * 推之前先查这条收藏在 Bangumi 上的更新时间：网页上更晚改过 → 以网页为准覆盖本地，不推送（以较新的一方为准）。
 * 只处理 Bangumi 来源、条目 id 为数字的记录；未登录或非官方 API 未确认时不推送。
 */
@Singleton
class BangumiOutbox @Inject constructor(
    private val bangumi: BangumiRemoteDataSource,
    private val userCollectionDao: UserCollectionDao,
    private val credentialStore: CredentialStore,
    private val settingsDataStore: SettingsDataStore,
    private val taskRecorder: com.acgcompass.data.tasks.TaskRunRecorder,
    private val dispatchers: DispatcherProvider,
) {
    private val lock = Mutex()

    /**
     * 推送待推送的记录；[onlyWorkIds] 不为空时只推这些作品。
     * 返回推送结果：成功推送的条数，或第一个阻止继续推送的错误（网络等，留待下次重试）。
     */
    suspend fun pushPending(onlyWorkIds: Collection<String>? = null): AppResult<Int> = withContext(dispatchers.io) {
        val canPushNow = canPush()
        val result = pushPendingLocked(onlyWorkIds)
        // 每次运行都记一笔（包括没有要推送的），「后台任务」页才能看出它确实跑过。
        taskRecorder.finish(com.acgcompass.domain.model.BackgroundTaskKind.OUTBOX, result) { pushed ->
            outboxSummary(canPushNow, pushed)
        }
        result
    }

    private suspend fun pushPendingLocked(onlyWorkIds: Collection<String>?): AppResult<Int> = lock.withLock {
        val username = settingsDataStore.bangumiUsername.first()
        if (!canPush() || username == null) return@withLock AppResult.Success(0)
        val filter = onlyWorkIds?.toSet()
        val pending = userCollectionDao.getAll()
            .filter { it.isPushable() && (filter == null || it.localWorkId in filter) }
        var pushed = 0
        for (record in pending) {
            when (val result = pushOne(username, record)) {
                is AppResult.Success -> if (result.data) pushed += 1
                is AppResult.Failure -> if (result.error.isTransient()) {
                    return@withLock AppResult.Failure(
                        result.error,
                    )
                }
            }
        }
        AppResult.Success(pushed)
    }

    /** 待推送的记录条数。 */
    suspend fun pendingCount(): Int = withContext(dispatchers.io) {
        userCollectionDao.getAll().count { it.isPushable() }
    }

    /** 有没有待推送的记录。 */
    suspend fun hasPending(): Boolean = withContext(dispatchers.io) {
        userCollectionDao.getAll().any { it.isPushable() }
    }

    /** 推一条：返回 true 表示推送了，false 表示网页上更新、以网页为准。 */
    private suspend fun pushOne(username: String, record: UserCollectionEntity): AppResult<Boolean> {
        val subjectId = record.sourceItemId.toIntOrNull() ?: return AppResult.Success(false)
        val now = System.currentTimeMillis()
        return when (val check = webAllowsPush(username, subjectId, record, now)) {
            is AppResult.Failure -> check
            is AppResult.Success -> if (check.data) upload(subjectId, record, now) else AppResult.Success(false)
        }
    }

    /** 网页上这条更晚改过 → 以网页为准写回本地，返回 false；网页上没有或更旧 → true。 */
    private suspend fun webAllowsPush(
        username: String,
        subjectId: Int,
        record: UserCollectionEntity,
        now: Long,
    ): AppResult<Boolean> = when (val remote = bangumi.getUserSubjectCollection(username, subjectId)) {
        is AppResult.Success -> {
            val remoteAt = parseBangumiTime(remote.data.updatedAt)
            if (remoteAt != null && remoteAt > record.updatedAt) {
                remote.data.toCollectionEntity(now)?.let { userCollectionDao.upsert(it) }
                AppResult.Success(false)
            } else {
                AppResult.Success(true)
            }
        }
        is AppResult.Failure ->
            if (remote.error is AppError.NotFound) AppResult.Success(true) else AppResult.Failure(remote.error)
    }

    private suspend fun upload(subjectId: Int, record: UserCollectionEntity, now: Long): AppResult<Boolean> {
        val result = bangumi.updateUserCollection(
            subjectId = subjectId,
            type = bangumiTypeOf(record.status),
            rate = record.rating ?: 0,
            comment = record.comment.orEmpty(),
            tags = record.tags,
            private = record.isPrivate,
        )
        if (result is AppResult.Failure) return AppResult.Failure(result.error)
        // 推送期间用户又改过这条时，保留「未同步」，下次再推。
        val latest = userCollectionDao.getByWork(record.localWorkId)
        if (latest != null && latest.updatedAt == record.updatedAt) {
            userCollectionDao.upsert(
                latest.copy(syncedAt = now),
            )
        }
        return AppResult.Success(true)
    }

    private suspend fun canPush(): Boolean {
        val configured = credentialStore.observeStatus().first()[CredentialSourceId.BANGUMI]?.configured == true
        val settings = settingsDataStore.settings.first()
        return configured && (settings.isBangumiOfficialApi || settings.bangumiNonOfficialTokenConsent)
    }

    private fun UserCollectionEntity.isPushable(): Boolean =
        isUnsynced() && source == CredentialSourceId.BANGUMI.name && sourceItemId.toIntOrNull() != null
}

/** 安排一次待推送任务（需要联网，失败按指数退避重试）。 */
@Singleton
class BangumiOutboxScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue() {
        val request = OneTimeWorkRequestBuilder<BangumiOutboxWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            BangumiOutboxWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    private companion object {
        const val BACKOFF_MINUTES = 2L
    }
}

@HiltWorker
class BangumiOutboxWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val outbox: BangumiOutbox,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        when (outbox.pushPending()) {
            is AppResult.Success -> Result.success()
            is AppResult.Failure -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }.getOrElse { if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success() }

    companion object {
        const val UNIQUE_WORK_NAME = "bangumi_outbox"
        private const val MAX_ATTEMPTS = 5
    }
}

/** 推送结果的一句话说明。 */
internal fun outboxSummary(canPush: Boolean, pushed: Int): String = when {
    !canPush -> "没登录 Bangumi（或没确认非官方地址），不推送"
    pushed > 0 -> "推送了 $pushed 条"
    else -> "没有要推送的修改"
}
