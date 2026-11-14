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
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.domain.model.SourceId
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 加入待补池 → Bangumi「想看」。
 *
 * 所有加入待补池的入口（详情页、探索队列、批量导入、今晚看什么、整个系列）都经过
 * [com.acgcompass.domain.repository.BacklogRepository.addAll]，在那里排队；后台任务逐个推送，失败的留到下次重试。
 * 推送前先查 Bangumi 上的收藏：已经有任何状态（看过 / 在看 / 搁置 / 抛弃 / 想看）就不动，绝不把「看过」改回「想看」。
 */
/** 加入待补池后需要回写 Bangumi「想看」的作品队列。 */
interface WishSyncQueue {
    suspend fun enqueue(workIds: Collection<String>)
}

@Singleton
@Suppress("LongParameterList") // 本地作品、匹配关系、收藏和待补池都要查，一一注入最直白
class BangumiWishSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsDataStore,
    private val credentialStore: CredentialStore,
    private val bangumi: BangumiRemoteDataSource,
    private val workDao: WorkDao,
    private val sourceLinkDao: SourceLinkDao,
    private val userCollectionDao: UserCollectionDao,
    private val backlogDao: BacklogDao,
) : WishSyncQueue {
    /** 把作品排进推送队列并安排后台任务。 */
    override suspend fun enqueue(workIds: Collection<String>) {
        if (workIds.isEmpty() || !settings.syncBacklogToBangumi.first()) return
        settings.updatePendingWish { it + workIds }
        schedule()
    }

    /**
     * 启动时调用：已有的待补池补同步一次（只排本地还没有更进一步状态的作品）；
     * 之前没推成的（比如当时没登录或没网）重新安排推送。
     */
    suspend fun resumeOnStartup() {
        if (!settings.syncBacklogToBangumi.first()) return
        if (!settings.wishBackfillDone.first()) {
            val ids = backlogDao.getAll().map { it.workId }.filter { id ->
                userCollectionDao.getByWork(id)?.status.let { it == null || it == WISH }
            }
            settings.updatePendingWish { it + ids }
            settings.setWishBackfillDone()
        }
        if (settings.pendingWishWorkIds.first().isNotEmpty()) schedule()
    }

    private fun schedule() {
        val request = OneTimeWorkRequestBuilder<BangumiWishSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(
            context,
        ).enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** 推送队列里的作品；返回是否还有因网络等原因没推成、需要重试的。 */
    suspend fun pushPending(): Boolean {
        val pending = settings.pendingWishWorkIds.first()
        val configured = credentialStore.observeStatus().first()[CredentialSourceId.BANGUMI]?.configured == true
        val username = settings.bangumiUsername.first()?.takeIf(String::isNotBlank)
        // 没登录或队列为空时什么都不做；队列保留，登录后下次启动会重新安排。
        if (pending.isEmpty() || !configured || username == null) return false
        var retry = false
        for (workId in pending) {
            when (pushOne(workId, username)) {
                Outcome.DONE, Outcome.SKIP -> settings.updatePendingWish { it - workId }
                Outcome.RETRY -> retry = true
            }
        }
        return retry
    }

    private enum class Outcome { DONE, SKIP, RETRY }

    private suspend fun pushOne(workId: String, username: String): Outcome {
        val subjectId = subjectIdOf(workId) ?: return Outcome.SKIP
        return when (val remote = bangumi.getUserSubjectCollection(username, subjectId)) {
            is AppResult.Success -> Outcome.SKIP
            is AppResult.Failure -> if (remote.error is AppError.NotFound) {
                when (bangumi.updateUserCollection(subjectId = subjectId, type = BANGUMI_WISH)) {
                    is AppResult.Success -> Outcome.DONE.also { recordLocally(workId, subjectId) }
                    is AppResult.Failure -> Outcome.RETRY
                }
            } else {
                Outcome.RETRY
            }
        }
    }

    /** Bangumi 条目 id：Bangumi 来源的作品就是作品 id，其它来源看是否匹配过 Bangumi 条目。VNDB 作品不推。 */
    private suspend fun subjectIdOf(workId: String): Int? {
        val work = workDao.getById(workId) ?: return null
        val primary = work.primarySource
        return when {
            primary == SourceId.BANGUMI.name -> workId.toIntOrNull()
            primary == SourceId.VNDB.name -> null
            else -> sourceLinkDao.getByWork(workId).firstOrNull { it.sourceId == SourceId.BANGUMI.name }
                ?.sourceItemId?.toIntOrNull()
        }
    }

    /** 推送成功后本地补一条「想看」，待补池和「我的库」保持一致。 */
    private suspend fun recordLocally(workId: String, subjectId: Int) {
        if (userCollectionDao.getByWork(workId) != null) return
        val now = System.currentTimeMillis()
        userCollectionDao.upsert(
            UserCollectionEntity(
                id = "${SourceId.BANGUMI.name}:$subjectId",
                source = SourceId.BANGUMI.name,
                sourceItemId = subjectId.toString(),
                localWorkId = workId,
                status = WISH,
                rating = null,
                progress = null,
                comment = null,
                tags = emptyList(),
                updatedAt = now,
                syncedAt = now,
                sourceUpdatedAt = null,
            ),
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "bangumi_wish_sync"
        private const val BANGUMI_WISH = 1
        private const val WISH = "想看"
        private const val BACKOFF_MINUTES = 5L
    }
}

/** 后台推送「想看」；有没推成的就按退避重试。 */
@HiltWorker
class BangumiWishSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val wishSync: BangumiWishSync,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = if (wishSync.pushPending()) Result.retry() else Result.success()
}
