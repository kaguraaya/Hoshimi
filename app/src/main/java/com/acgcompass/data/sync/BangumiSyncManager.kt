package com.acgcompass.data.sync

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiTokenRefresher
import com.acgcompass.data.remote.bangumi.BangumiUserSubjectCollectionDto
import com.acgcompass.data.remote.bangumi.toBasicWorkFeature
import com.acgcompass.data.remote.bangumi.toWork
import com.acgcompass.data.repository.WorkTagWriter
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.domain.taste.WorkFeatureCompleteness
import com.acgcompass.domain.taste.WorkFeatureRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一次 Bangumi 同步的结果统计。 */
data class SyncReport(
    val added: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val total: Int = 0,
    /** Bangumi 上已删除、移进回收站的条数。 */
    val removed: Int = 0,
    /** 本地更新、推到 Bangumi 的条数。 */
    val pushed: Int = 0,
    /** 这次没写进本地的部分（如「标签」「待补池」），收藏本身已经同步。 */
    val incomplete: List<String> = emptyList(),
) {
    /** 有部分资料没写进本地时的一句说明，没有时为 null。 */
    val incompleteNote: String?
        get() = incomplete.takeIf { it.isNotEmpty() }?.let { "，${it.joinToString("、")}没写进去" }

    fun summary(username: String): String = buildString {
        append("已从 Bangumi @$username 同步：新增 $added · 更新 $updated")
        if (removed > 0) append(" · 移入回收站 $removed")
        if (pushed > 0) append(" · 推送本地修改 $pushed")
        if (skipped > 0) append(" · 跳过 $skipped")
        if (failed > 0) append(" · 失败 $failed")
        append("（共 $total）")
        incompleteNote?.let(::append)
    }
}

/** 切换 Bangumi 账号时，用户对旧账号收藏的选择。 */
enum class AccountSwitchChoice {
    /** 把本地收藏合并进新账号（推送到 Bangumi）。 */
    MERGE,

    /** 把旧账号的收藏移进回收站。 */
    MOVE_TO_TRASH,

    /** 暂不处理，同步保持暂停。 */
    CANCEL,
}

/**
 * Bangumi 用户数据同步。分页拉取当前用户收藏，映射并写入本地 Room（[WorkDao] 和
 * [UserCollectionDao]），供我的页统计 / 详情页我的记录 / 口味画像 / 时光机消费。
 *
 * 凭据门控：未配置 Token 时返回 [AppResult.Failure]（Unauthorized），不发请求。
 * 错误处理：getMe / 分页失败均映射为带可读原因的 [AppError]（401/403/网络/超时/解析）；
 * 单个请求遇到网络、超时、服务端错误时短暂重试（[retryTransient]）。
 * 写入为 best-effort 幂等：以 `BANGUMI:<subjectId>` 为主键 upsert，重复同步只更新不重复。
 *
 * 同一时间只跑一次同步：首页、我的、时光机、口味分析和后台任务同时触发时，后来的调用等待并共享
 * 正在进行的那一次的结果。同步开始前先按需续期 OAuth token（后台任务可能在用户多天没打开应用时运行）。
 *
 * 合并规则见 [planCollectionMerge]：以较新的一方为准；在应用里移除过的不会被加回来；Bangumi 上删除的移进本地回收站；
 * 本地更新的修改由 [BangumiOutbox] 推到 Bangumi。发现换了账号时暂停同步，等用户在 [resolveAccountSwitch] 里选择。
 */
@Singleton
@Suppress("LongParameterList")
class BangumiSyncManager @Inject constructor(
    private val bangumi: BangumiRemoteDataSource,
    private val credentialStore: CredentialStore,
    private val userCollectionDao: UserCollectionDao,
    private val workDao: WorkDao,
    private val workTagWriter: WorkTagWriter,
    private val backlogRepository: com.acgcompass.domain.repository.BacklogRepository,
    private val settingsDataStore: SettingsDataStore,
    private val syncStatusRepository: SyncStatusRepository,
    private val workFeatureRepository: WorkFeatureRepository,
    private val featureEnrichmentScheduler: WorkFeatureEnrichmentScheduler,
    private val tokenRefresher: BangumiTokenRefresher,
    private val trash: com.acgcompass.data.repository.TrashRepositoryImpl,
    private val outbox: BangumiOutbox,
    private val taskRecorder: com.acgcompass.data.tasks.TaskRunRecorder,
    private val transaction: com.acgcompass.data.local.LocalTransactionRunner,
    private val dispatchers: DispatcherProvider,
) {
    /** 同步在自己的作用域里跑：发起同步的页面离开时，同步本身不会被取消。 */
    private val syncScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val inFlightLock = Mutex()
    private var inFlight: Deferred<AppResult<SyncReport>>? = null

    /** 是否已配置 Bangumi 凭据（个人同步前置条件）。 */
    suspend fun isConfigured(): Boolean =
        credentialStore.observeStatus().first()[CredentialSourceId.BANGUMI]?.configured == true

    /**
     * 同步当前 Bangumi 用户的全部收藏到本地。
     * @return 成功时返回 [SyncReport]；失败时返回带可读原因的 [AppResult.Failure]。
     *
     * 无论成功或失败，结果都会写入 [SyncStatusRepository]，使各页（首页/我的/时光机/口味）
     * 读取到同一份同步状态，且重启后仍可见。
     */
    suspend fun syncCollections(): AppResult<SyncReport> {
        val run = inFlightLock.withLock {
            inFlight?.takeIf { it.isActive } ?: syncScope.async { syncAndRecord() }.also { inFlight = it }
        }
        return run.await()
    }

    private suspend fun syncAndRecord(): AppResult<SyncReport> {
        taskRecorder.started(com.acgcompass.domain.model.BackgroundTaskKind.SYNC)
        runCatchingCancellable { tokenRefresher.refreshIfNeeded() }
        val result = when (val raw = runSyncInternal()) {
            is AppResult.Failure -> AppResult.Failure(raw.error.withBangumiApiHint())
            is AppResult.Success -> raw
        }
        when (result) {
            is AppResult.Success -> syncStatusRepository.recordSuccess(result.data)
            is AppResult.Failure -> syncStatusRepository.recordFailure(result.error.cause)
        }
        taskRecorder.finish(com.acgcompass.domain.model.BackgroundTaskKind.SYNC, result) { report ->
            "共 ${report.total} 部，新增 ${report.added}、更新 ${report.updated}"
        }
        return result
    }

    private suspend fun runSyncInternal(): AppResult<SyncReport> {
        if (!isConfigured()) {
            return AppResult.Failure(
                AppError.Unauthorized(cause = "未配置 Bangumi 账号，无法同步个人数据"),
            )
        }
        // 个人同步会携带 Token；非官方 API 且未确认风险时拒绝，提示去设置确认。
        val settings = settingsDataStore.settings.first()
        if (!settings.isBangumiOfficialApi && !settings.bangumiNonOfficialTokenConsent) {
            return AppResult.Failure(
                AppError.Unauthorized(
                    cause = "当前为非官方 Bangumi API；个人同步需先在「设置 → Bangumi」确认 Token 隐私风险",
                ),
            )
        }
        val username = when (val me = retryTransient { bangumi.getMe() }) {
            is AppResult.Failure -> return AppResult.Failure(me.error)
            is AppResult.Success -> me.data.username.ifBlank { me.data.nickname }
        }
        if (username.isBlank()) {
            return AppResult.Failure(AppError.Unauthorized(cause = "无法获取 Bangumi 用户名"))
        }
        // 换了账号：暂停同步，等用户选择怎么处理旧账号的收藏（合并 / 移进回收站）。
        val lastUser = settingsDataStore.lastSyncedBangumiUser.first()
        if (lastUser != null && lastUser != username) {
            settingsDataStore.setPendingAccountSwitch(lastUser, username)
            return AppResult.Failure(
                AppError.Unauthorized(cause = ACCOUNT_SWITCH_MESSAGE, nextStep = ACCOUNT_SWITCH_NEXT),
            )
        }
        runCatchingCancellable { settingsDataStore.setBangumiUsername(username) }
        val fetched = when (val f = fetchAll(username)) {
            is AppResult.Failure -> return AppResult.Failure(f.error)
            is AppResult.Success -> f.data
        }
        val report = applyMerge(fetched, System.currentTimeMillis())
        runCatchingCancellable { settingsDataStore.setLastSyncedBangumiUser(username) }
        return AppResult.Success(report)
    }

    private suspend fun fetchAll(username: String): AppResult<FetchedCollections> {
        val items = mutableListOf<BangumiUserSubjectCollectionDto>()
        var total = 0
        var failedPages = 0
        var pages = 0
        while (pages < MAX_PAGES) {
            val page = retryTransient {
                bangumi.getUserCollections(username = username, limit = PAGE_SIZE, offset = items.size)
            }
            when (page) {
                is AppResult.Failure -> {
                    // 首页就失败 → 整体失败；后续页失败 → 记下并停止（这次不处理删除）。
                    if (items.isEmpty()) return AppResult.Failure(page.error)
                    failedPages += 1
                    break
                }
                is AppResult.Success -> {
                    total = page.data.total
                    if (page.data.data.isEmpty()) break
                    items += page.data.data
                    pages += 1
                    if (items.size >= total) break
                }
            }
        }
        if (pages >= MAX_PAGES && items.size < total) failedPages += 1
        return AppResult.Success(FetchedCollections.of(items, total, failedPages))
    }

    @Suppress("LongMethod") // 按合并计划依次写入作品、特征、收藏、待补池、回收站并推送，拆开反而难核对顺序
    private suspend fun applyMerge(fetched: FetchedCollections, now: Long): SyncReport {
        val dtoByWork = LinkedHashMap<String, BangumiUserSubjectCollectionDto>()
        val remote = fetched.items.mapNotNull { dto ->
            dto.toCollectionEntity(now)?.let { entity ->
                dtoByWork[entity.localWorkId] = dto
                RemoteCollection(entity, parseBangumiTime(dto.updatedAt))
            }
        }
        val local = userCollectionDao.getAll().associateBy { it.localWorkId }
        val plan = planCollectionMerge(
            remote = remote,
            local = local,
            removals = trash.collectionRemovals(),
            pendingWish = settingsDataStore.pendingWishWorkIds.first(),
            complete = fetched.complete,
        )
        // 先写入收藏响应内嵌的特征，再发布个人收藏，避免画像观察器看到个人记录已更新而特征池仍为空。
        val lightweightFeatures = embeddedFeatures(plan.upserts, dtoByWork, now)
        if (lightweightFeatures.isNotEmpty()) {
            workFeatureRepository.seedLightweight(lightweightFeatures)
            featureEnrichmentScheduler.enqueue()
        }
        // 作品、收藏和移除标记按批放进短事务一起写（见 [CollectionMergeWriter]）。
        val written = CollectionMergeWriter(transaction, workDao, userCollectionDao, trash::clearCollectionRemoval)
            .write(
                upserts = plan.upserts,
                works = plan.upserts.mapNotNull { e ->
                    dtoByWork[e.localWorkId]?.subject?.toWork()?.let { e.localWorkId to it }
                }.toMap(),
                local = local,
                clearRemovals = plan.clearRemovals,
                obsoleteMarkers = plan.obsoleteMarkers,
                now = now,
            )
        val added = written.added
        val syncedWorks = written.syncedWorks
        val backlogWorks = written.backlogWorks
        val batchId = java.util.UUID.randomUUID().toString()
        val removed = plan.remoteRemoved.count {
            trash.trashCollection(it, com.acgcompass.domain.model.TrashReason.REMOTE_REMOVED, batchId, now)
        }
        val incomplete = buildList {
            val works = syncedWorks.distinctBy { it.id }
            if (!retryLocalWrite { workTagWriter.persist(works) }) add("标签")
            val backlog = backlogWorks.distinctBy { it.id }
            if (backlog.isNotEmpty() && !retryLocalWrite { backlogRepository.addAll(backlog) }) add("待补池")
        }
        val pushed = if (plan.pushLocal.isEmpty()) {
            0
        } else {
            (outbox.pushPending(plan.pushLocal) as? AppResult.Success)?.data ?: 0
        }
        return SyncReport(
            added = added,
            updated = plan.upserts.size - added,
            skipped = fetched.items.size - remote.size + plan.keptRemoved.size,
            failed = fetched.failedPages,
            total = fetched.total,
            removed = removed,
            pushed = pushed,
            incomplete = incomplete,
        )
    }

    /** 收藏响应里内嵌的条目资料（标签、评分等），先存成轻量作品资料。 */
    private fun embeddedFeatures(
        upserts: List<UserCollectionEntity>,
        dtoByWork: Map<String, BangumiUserSubjectCollectionDto>,
        now: Long,
    ): List<WorkFeature> = upserts.mapNotNull { entity ->
        dtoByWork[entity.localWorkId]?.subject?.toBasicWorkFeature(
            subjectId = entity.sourceItemId,
            updatedAt = now,
            completeness = WorkFeatureCompleteness.EMBEDDED,
        )
    }

    /**
     * 处理账号切换：
     * - [AccountSwitchChoice.MERGE]：本地收藏全部标记为「未同步」，同步时以较新的一方为准，本地有而新账号没有的推到新账号；
     * - [AccountSwitchChoice.MOVE_TO_TRASH]：旧账号的收藏全部移进回收站（可恢复，恢复后推到新账号）；
     * - [AccountSwitchChoice.CANCEL]：只关掉提示，同步保持暂停，下次同步会再问。
     * 选择合并或移进回收站后立即同步一次。
     */
    suspend fun resolveAccountSwitch(choice: AccountSwitchChoice): AppResult<SyncReport>? {
        val pending = settingsDataStore.pendingAccountSwitch.first()
        if (pending != null) settingsDataStore.clearPendingAccountSwitch()
        if (pending == null || choice == AccountSwitchChoice.CANCEL) return null
        applyAccountChoice(choice)
        settingsDataStore.setLastSyncedBangumiUser(pending.second)
        return syncCollections()
    }

    /** 合并：旧账号的收藏全部标记为未同步，推到新账号；移到回收站：旧账号的收藏作为一批移进回收站。 */
    private suspend fun applyAccountChoice(choice: AccountSwitchChoice) {
        val bangumiRecords = userCollectionDao.getAll().filter { it.source == CredentialSourceId.BANGUMI.name }
        when (choice) {
            AccountSwitchChoice.MERGE -> userCollectionDao.upsertAll(bangumiRecords.map { it.copy(syncedAt = 0L) })
            AccountSwitchChoice.MOVE_TO_TRASH -> {
                val batchId = java.util.UUID.randomUUID().toString()
                bangumiRecords.forEach {
                    trash.trashCollection(
                        it.localWorkId,
                        com.acgcompass.domain.model.TrashReason.ACCOUNT_SWITCH,
                        batchId,
                    )
                }
            }
            AccountSwitchChoice.CANCEL -> Unit
        }
    }

    private companion object {
        const val PAGE_SIZE = 50

        /** 最多 200 页（1 万条收藏）。 */
        const val MAX_PAGES = 200

        const val ACCOUNT_SWITCH_MESSAGE = "检测到切换了 Bangumi 账号，同步已暂停"
        const val ACCOUNT_SWITCH_NEXT = "请在弹出的提示里选择如何处理旧账号的收藏"
    }
}

internal fun String?.isWishOrWatching(): Boolean = this == "想看" || this == "在看"

/**
 * 一次拉取的收藏：按条目编号去重后的全部收藏、接口报的总数，以及有没有分页失败。
 * 分页过程中有人增删收藏时，同一条可能出现在两页里、也可能被挤到没取到的位置，所以按去重后的条数判断有没有拿全。
 */
internal data class FetchedCollections(
    val items: List<BangumiUserSubjectCollectionDto>,
    val total: Int,
    val failedPages: Int,
) {
    /** 确实拿全了：没有分页失败，去重后的条数不少于总数。只有这时才处理「Bangumi 上已删除」。 */
    val complete: Boolean get() = failedPages == 0 && items.size >= total

    companion object {
        fun of(raw: List<BangumiUserSubjectCollectionDto>, total: Int, failedPages: Int) =
            FetchedCollections(raw.distinctBy { it.subjectId }, total, failedPages)
    }
}

private val syncLogger = com.acgcompass.core.common.logging.RedactingLogger()

/** 写本地数据库失败时再试一次；仍失败返回 false 并记日志（不影响其它部分的同步）。 */
@Suppress("TooGenericExceptionCaught") // 任何写库异常都只记为「这部分没写进去」
private suspend fun retryLocalWrite(block: suspend () -> Unit): Boolean {
    repeat(2) { attempt ->
        try {
            block()
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt > 0) syncLogger.w("BangumiSync", "同步时写入本地数据失败：${e::class.simpleName}")
        }
    }
    return false
}
