package com.acgcompass.feature.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.sync.BangumiSyncManager
import com.acgcompass.data.sync.SyncStatusRepository
import com.acgcompass.domain.repository.BacklogRepository
import com.acgcompass.domain.repository.PersonalCollectionRepository
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 「我的」页 ViewModel，使用 MVVM、Hilt 和 StateFlow。
 *
 * 合并凭据元数据、待补池、本地作品、**已同步的用户收藏**为 [MineUiState]。已同步时统计基于真实
 * Bangumi 个人数据（看过/在看/想看/搁置/抛弃/平均分/最高分/常见标签）；未同步回退本地待补池。
 *
 * 提供 [onSyncBangumi] 触发 [BangumiSyncManager] 真正拉取并入库，结果经 [syncMessage] 反馈。
 *
 * 安全：账号区只读元数据；明文凭据绝不进入 VM/UI。
 */
@HiltViewModel
class MineViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val backlogRepository: BacklogRepository,
    private val workRepository: WorkRepository,
    private val personalCollections: PersonalCollectionRepository,
    private val bangumiSyncManager: BangumiSyncManager,
    private val syncStatusRepository: SyncStatusRepository,
) : ViewModel() {

    private val _syncMessage = MutableStateFlow<String?>(null)

    /** 同步结果一次性提示。 */
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    val uiState: StateFlow<UiState<MineUiState>> =
        combine(
            credentialStore.observeStatus(),
            backlogRepository.observeBacklog(),
            workRepository.observeWorks(),
            personalCollections.observeAll(),
            syncStatusRepository.status,
        ) { statusMap, backlog, works, collections, syncStatus ->
            val stats = buildMineStats(
                works = works,
                backlog = backlog,
                collections = collections.map {
                    UserCollectionStat(
                        workId = it.workId,
                        status = it.status,
                        rating = it.rating,
                    )
                },
            )
            MineUiState(
                accounts = buildAccountRows(statusMap),
                stats = stats,
                syncStatus = syncStatus,
            )
        }
            .map<MineUiState, UiState<MineUiState>> { UiState.Success(it) }
            .catch { throwable ->
                if (throwable is CancellationException) throw throwable
                emit(UiState.Error(AppError.Server()))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = UiState.Loading,
            )

    /** 从 Bangumi 同步个人收藏到 Room；写入后统计流自动刷新。 */
    fun onSyncBangumi() {
        if (_syncing.value) return
        _syncing.value = true
        viewModelScope.launch {
            try {
                when (val r = bangumiSyncManager.syncCollections()) {
                    is AppResult.Success -> {
                        val rep = r.data
                        _syncMessage.value =
                            "已从 Bangumi 同步：新增 ${rep.added} · 更新 ${rep.updated} · " +
                            "跳过 ${rep.skipped} · 失败 ${rep.failed}（共 ${rep.total}）" + rep.incompleteNote.orEmpty()
                    }
                    is AppResult.Failure -> _syncMessage.value = "同步失败：${r.error.cause}"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _syncMessage.value = "同步失败，请稍后再试"
            } finally {
                _syncing.value = false
            }
        }
    }

    fun clearSyncMessage() {
        _syncMessage.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
