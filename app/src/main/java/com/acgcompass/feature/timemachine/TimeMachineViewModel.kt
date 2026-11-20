package com.acgcompass.feature.timemachine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.sync.BangumiSyncManager
import com.acgcompass.data.sync.SyncStatusRepository
import com.acgcompass.domain.detail.PersonalRecord
import com.acgcompass.domain.model.CollectionState
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.PersonalCollectionRepository
import com.acgcompass.domain.repository.TimeMachineRepository
import com.acgcompass.domain.repository.WorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 本地时光机 ViewModel。MVVM + Hilt + StateFlow。
 *
 * 合并 [TimeMachineRepository.observeChangeLogs]（变更日志，单一可信源 = Room）与
 * [WorkRepository.observeWorks]（标题解析），经纯函数 [buildTimeMachineUiState] 折叠为
 * 时间线 + 月/年报告。无变更时呈现空态（不伪造）。
 *
 *  * - 本地时光机**无需登录即可使用**：从待补池建立初始快照 / 记录变化（[onCaptureLocalSnapshot]）。
 * - Bangumi 同步作为增强：凭据门控；未配置时给出明确提示而非无反应；已配置时验证连接并说明
 *   冲突安全策略（云端→本地只读合并，本地→云端写入需显式确认以免覆盖）。
 *
 * 韧性：流异常兜底为 [UiState.Error]，绝不崩溃。
 */
@HiltViewModel
class TimeMachineViewModel
@Suppress("LongParameterList") // Hilt 注入的依赖，逐个都是页面真实用到的
@Inject
constructor(
    private val timeMachineRepository: TimeMachineRepository,
    private val workRepository: WorkRepository,
    private val backlogRepository: BacklogRepository,
    private val bangumiSyncManager: BangumiSyncManager,
    private val personalCollections: PersonalCollectionRepository,
    private val syncStatusRepository: SyncStatusRepository,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)

    /** 一次性用户提示（同步 / 快照结果）；消费后调用 [clearMessage]。 */
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _syncing = MutableStateFlow(false)

    /** 是否正在执行快照 / 同步操作（用于禁用按钮，避免重复点击）。 */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    val uiState: StateFlow<UiState<TimeMachineUiState>> =
        combine(
            timeMachineRepository.observeChangeLogs(),
            workRepository.observeWorks(),
            timeMachineRepository.observeSnapshots(),
            personalCollections.observeAll(),
            syncStatusRepository.status,
        ) { changeLogs, works, snapshots, collections, syncStatus ->
            val titlesById = works.associate { it.id to it.titles.canonical }
            val baselineGroups = buildBaselineGroups(collections.toBaselineItems(works))
            val overview = buildOverview(
                collectionCount = collections.size,
                lastSyncText = syncStatus.lastSyncText(),
                recentChangeCount = changeLogs.size,
                ratings = collections.mapNotNull { it.rating },
            )
            when {
                changeLogs.isNotEmpty() ->
                    UiState.Success(
                        buildTimeMachineUiState(changeLogs, titlesById).copy(
                            overview = overview,
                            baselineGroups = baselineGroups,
                            baselineHeadline = baselineHeadline(collections.size),
                        ),
                    )
                // 已建立初始快照但没有变更日志时，展示完整历史时间线。
                snapshots.isNotEmpty() || collections.isNotEmpty() -> {
                    val takenAt = snapshots.firstOrNull()?.takenAt ?: System.currentTimeMillis()
                    val historyTimeline = buildCollectionTimeline(collections.toTimelineInputs(works))
                    UiState.Success(
                        baselineUiState(
                            collectionCount = collections.size,
                            takenAt = takenAt,
                            overview = overview,
                            baselineGroups = baselineGroups,
                            historyTimeline = historyTimeline,
                        ),
                    )
                }
                else -> UiState.Empty(EMPTY_CTA)
            }
        }
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                emit(UiState.Error(AppError.Server()))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading,
            )

    /** 收藏数 → 「导入了 N 部收藏」提示；为 0 时不显示。 */
    private fun baselineHeadline(collectionCount: Int): String? =
        if (collectionCount > 0) "导入了 $collectionCount 部收藏" else null

    /** 用户收藏 → 历史时间线输入（时间优先取 Bangumi 源更新时间，回退本地同步/更新时间）。 */
    private fun List<PersonalRecord>.toTimelineInputs(works: List<Work>): List<CollectionTimelineInput> {
        val titleById = works.associate { it.id to it.titles.canonical }
        return map { c ->
            val ts = c.sourceUpdatedAt?.let { parseIsoMillis(it) } ?: c.updatedAt.takeIf { it > 0 } ?: c.syncedAt
            CollectionTimelineInput(
                workId = c.workId,
                title = titleById[c.workId] ?: NO_DATA,
                status = c.status,
                rating = c.rating,
                comment = c.comment,
                progress = c.progress,
                timestamp = ts,
            )
        }
    }

    /** 解析 ISO-8601 时间串为毫秒；失败返回 null。 */
    private fun parseIsoMillis(iso: String): Long? =
        runCatchingCancellable { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()

    /** 将用户收藏和作品信息映射为可浏览的基线条目。 */
    private fun List<PersonalRecord>.toBaselineItems(works: List<Work>): List<BaselineItem> {
        val worksById = works.associateBy { it.id }
        return map { c ->
            val work = worksById[c.workId]
            BaselineItem(
                workId = c.workId,
                title = work?.titles?.canonical ?: NO_DATA,
                typeLabel = work?.mediaType.toTypeLabel(),
                status = c.status,
                rating = c.rating,
                progress = c.progress,
                syncedAt = c.syncedAt,
            )
        }
    }

    /** 媒介类型 → 中文标签（缺失回退「暂无数据」，不伪造）。 */
    private fun MediaType?.toTypeLabel(): String = when (this) {
        MediaType.ANIME -> "动画"
        MediaType.MANGA -> "漫画"
        MediaType.NOVEL -> "小说"
        MediaType.GAME -> "游戏"
        MediaType.VN -> "视觉小说"
        MediaType.OTHER -> "其他"
        null -> NO_DATA
    }

    /**
     * 仅有初始快照、尚无变更时的基线视图：展示已记录作品数 + 可浏览作品列表，
     * 提示后续变化将出现在此。
     */
    private fun baselineUiState(
        collectionCount: Int,
        takenAt: Long,
        overview: TimeMachineOverviewUi,
        baselineGroups: List<BaselineGroupUi>,
        historyTimeline: List<TimelinePeriodUi> = emptyList(),
    ): TimeMachineUiState = TimeMachineUiState(
        timeline = historyTimeline.ifEmpty {
            listOf(
                TimelinePeriodUi(
                    label = "基线快照",
                    entries = listOf(
                        TimelineEntryUi(
                            workTitle = "初始快照",
                            description = "已记录 $collectionCount 个作品的当前状态；后续状态 / 评分 / 进度 / 短评变化将在此显示。",
                            changedAt = takenAt,
                        ),
                    ),
                ),
            )
        },
        report = TimeMachineReportUi(
            totalChanges = 0,
            addedCount = collectionCount,
            statusChanges = 0,
            ratingChanges = 0,
            reviewChanges = 0,
            progressChanges = 0,
            avgNewRatingText = NO_DATA,
            maxNewRatingText = NO_DATA,
            ratingFlipflops = emptyList(),
            pastThoughts = emptyList(),
        ),
        overview = overview,
        baselineGroups = baselineGroups,
        baselineHeadline = baselineHeadline(collectionCount),
    )

    /**
     * 建立本地快照（无需登录）。优先用已同步的用户收藏（含状态/评分/进度/短评），否则回退待补池。
     * 两者皆空时给出明确提示。首个快照为初始基线（不产生变更），其后记录差异。
     */
    fun onCaptureLocalSnapshot() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            try {
                val collections = personalCollections.getAll()
                val states: List<CollectionState> = if (collections.isNotEmpty()) {
                    collections.map { c ->
                        CollectionState(
                            workId = c.workId,
                            status = c.status,
                            rating = c.rating,
                            shortReview = c.comment,
                            progress = c.progress,
                        )
                    }
                } else {
                    backlogRepository.observeBacklog().first()
                        .map { CollectionState(workId = it.workId, status = "想看") }
                }
                if (states.isEmpty()) {
                    _message.value = "当前没有本地记录，先添加作品或从 Bangumi 同步。"
                    return@launch
                }
                _message.value = applySnapshot(states)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _message.value = "拍快照失败，请稍后再试"
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * 从 Bangumi 导入并合并到本地时间线：
     * 1) [BangumiSyncManager] 分页拉取用户收藏写入 Room；
     * 2) 读取入库收藏映射为 [CollectionState]，无快照则建初始基线，否则记录差异生成 change log；
     * 3) 反馈同步统计。未配置 / 失败显示具体原因，本地时光机不受影响。
     */
    fun onSyncFromBangumi() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            try {
                if (!bangumiSyncManager.isConfigured()) {
                    _message.value =
                        "未配置 Bangumi 账号。本地时光机已可使用；在「设置 → 数据源」登录后可从云端导入"
                    return@launch
                }
                when (val r = bangumiSyncManager.syncCollections()) {
                    is AppResult.Failure -> {
                        _message.value = "Bangumi 导入失败：${r.error.cause}。本地时光机不受影响"
                        return@launch
                    }
                    is AppResult.Success -> {
                        val rep = r.data
                        // 用入库后的收藏构建快照（与本地快照统一时间线）。
                        val states = personalCollections.getAll().map { c ->
                            CollectionState(
                                workId = c.workId,
                                status = c.status,
                                rating = c.rating,
                                shortReview = c.comment,
                                progress = c.progress,
                            )
                        }
                        // 隐私开关「记录本地时光机快照」：关闭时同步照常进行，但不把变化写进时光机。
                        val snapResult = if (settingsDataStore.recordTimeMachineSnapshots.first()) {
                            applySnapshot(states)
                        } else {
                            "已关闭时光机快照记录，本次变化未写入"
                        }
                        _message.value =
                            buildString {
                                append("已从 Bangumi 导入：新增 ${rep.added} · 更新 ${rep.updated} · 跳过 ${rep.skipped} · ")
                                append("失败 ${rep.failed}（共 ${rep.total}）${rep.incompleteNote.orEmpty()}。$snapResult")
                            }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _message.value = "Bangumi 导入失败，请稍后再试。本地时光机不受影响"
            } finally {
                _syncing.value = false
            }
        }
    }

    /** 把给定收藏状态写入时光机：无快照建初始基线，否则记差异。返回结果文案。 */
    private suspend fun applySnapshot(states: List<CollectionState>): String {
        if (states.isEmpty()) return "暂无可记录的收藏"
        val existing = timeMachineRepository.observeSnapshots().first()
        return if (existing.isEmpty()) {
            timeMachineRepository.captureInitialSnapshot(states)
            "已建立初始快照（${states.size} 个作品）"
        } else {
            val logs = timeMachineRepository.recordSyncDiff(states)
            if (logs.isEmpty()) "暂无新变化" else "记录 ${logs.size} 条变化"
        }
    }

    /** 消费一次性提示。 */
    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        val EMPTY_CTA = Cta(label = "拍一张本地快照，开始记录你的补番轨迹", action = "snapshot")
    }
}
