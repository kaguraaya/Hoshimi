package com.acgcompass.feature.cache

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.domain.cache.CacheAssetKind
import com.acgcompass.domain.cache.CacheClearRequest
import com.acgcompass.domain.cache.CacheLimitMode
import com.acgcompass.domain.repository.CacheFillController
import com.acgcompass.domain.repository.CacheRepository
import com.acgcompass.domain.repository.LocalDataResetter
import com.acgcompass.domain.repository.WorkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CacheManagementViewModel @Inject constructor(
    private val cacheRepository: CacheRepository,
    private val workRepository: WorkRepository,
    private val cacheFillController: CacheFillController,
    private val settings: SettingsDataStore,
    private val localDataResetter: LocalDataResetter,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val busy = MutableStateFlow(false)
    private val filling = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private var fillJob: Job? = null

    private val content = combine(
        cacheRepository.observeUsage(),
        cacheRepository.observeItems(),
        settings.settings,
        query,
        cacheRepository.observeFillRuns(),
    ) { usage, items, state, query, fillRuns ->
        CacheManagementUiState(
            policy = com.acgcompass.domain.cache.CachePolicy(
                mode = state.cacheLimitMode,
                countLimit = state.autoCacheMaxCount,
                spaceLimitBytes = state.cacheSpaceLimitMb.toLong() * 1024L * 1024L,
                backgroundFillEnabled = state.cacheBackgroundFillEnabled,
            ),
            usage = usage,
            items = items,
            query = query,
            fillRuns = fillRuns,
        )
    }

    private val interaction = combine(selected, busy, filling, message) { selected, busy, filling, message ->
        CacheInteraction(selected, busy, filling, message)
    }

    val uiState = combine(content, interaction) { state, interaction ->
        state.copy(
            selectedWorkIds = interaction.selected,
            busy = interaction.busy || interaction.filling,
            fillInProgress = interaction.filling,
            message = interaction.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CacheManagementUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun toggleSelection(workId: String) {
        selected.value = if (workId in selected.value) selected.value - workId else selected.value + workId
    }

    fun setMode(mode: CacheLimitMode) = updateAndTrim { settings.setCacheLimitMode(mode) }

    fun setCountLimit(count: Int) = updateAndTrim { settings.setAutoCacheMaxCount(count) }

    fun setSpaceLimitMb(megabytes: Int) = updateAndTrim { settings.setCacheSpaceLimitMb(megabytes) }

    fun setBackgroundFill(enabled: Boolean) {
        viewModelScope.launch { settings.setCacheBackgroundFillEnabled(enabled) }
    }

    fun onPolicyEvent(event: CachePolicyEvent) {
        when (event) {
            is CachePolicyEvent.ModeChanged -> setMode(event.mode)
            is CachePolicyEvent.CountLimitChanged -> setCountLimit(event.value)
            is CachePolicyEvent.SpaceLimitChanged -> setSpaceLimitMb(event.value)
            is CachePolicyEvent.BackgroundFillChanged -> setBackgroundFill(event.enabled)
            CachePolicyEvent.StartFill -> startFill()
            CachePolicyEvent.StopFill -> stopFill()
        }
    }

    fun startFill() {
        if (fillJob?.isActive == true) return
        fillJob = viewModelScope.launch {
            filling.value = true
            message.value = null
            try {
                message.value = when (val result = workRepository.loadPublicDiscovery(background = false)) {
                    is AppResult.Success -> fillCompletionMessage()
                    is AppResult.Failure -> listOf(result.error.cause, result.error.nextStep)
                        .filter(String::isNotBlank)
                        .joinToString("；")
                        .ifBlank { "填充未完成，请稍后重试" }
                }
            } catch (_: CancellationException) {
                // stopFill owns the user-facing stopped state.
            } finally {
                filling.value = false
                fillJob = null
            }
        }
    }

    private suspend fun fillCompletionMessage(): String {
        val state = settings.settings.first()
        val usage = cacheRepository.observeUsage().first()
        return if (state.cacheLimitMode == CacheLimitMode.COUNT) {
            val target = state.autoCacheMaxCount
            if (usage.publicWorkCount >= target) {
                "已达到公共缓存上限：${usage.publicWorkCount}/$target 部"
            } else {
                "本轮填充已结束：当前 ${usage.publicWorkCount}/$target 部，仍可继续补充"
            }
        } else {
            "本轮填充已结束：公共缓存 ${usage.publicWorkCount} 部，仍按空间上限管理"
        }
    }

    fun stopFill() {
        val wasRunning = filling.value || fillJob?.isActive == true
        cacheFillController.cancelPublicDiscoveryFill()
        fillJob?.cancel()
        fillJob = null
        filling.value = false
        if (wasRunning) message.value = "已停止缓存填充，已写入的数据会保留"
    }

    fun trimNow() = runOperation {
        val result = cacheRepository.trimToPolicy()
        "已整理：移除 ${result.removedWorks} 部作品、${result.removedAssets} 个图片文件"
    }

    fun clearAllCache() = runOperation {
        val result = cacheRepository.clearAllPublicCache()
        "已清除 ${result.removedWorks} 部公共作品缓存，个人记录已保留"
    }

    fun clearExpiredDetails() = runOperation {
        cacheRepository.clear(CacheClearRequest(expiredDetailsOnly = true))
        "已清理过期详情"
    }

    fun clearAsset(kind: CacheAssetKind) = runOperation {
        val result = cacheRepository.clear(CacheClearRequest(assetKinds = setOf(kind)))
        "已清理 ${result.removedAssets} 个图片文件"
    }

    fun clearSource(source: com.acgcompass.domain.model.SourceId) = runOperation {
        val result = cacheRepository.clear(CacheClearRequest(sources = setOf(source)))
        "已清理 ${result.removedWorks} 部 ${source.name} 公共缓存"
    }

    fun clearSelected(kind: CacheAssetKind? = null) = runOperation {
        val ids = selected.value
        val result = cacheRepository.clear(
            CacheClearRequest(
                workIds = ids,
                assetKinds = kind?.takeUnless { it == CacheAssetKind.WORK }?.let(::setOf).orEmpty(),
            ),
        )
        selected.value = emptySet()
        "已清理 ${result.removedWorks} 部作品、${result.removedAssets} 项资源；" +
            "${result.protectedSkipped} 部受个人记录保护"
    }

    fun clearOne(workId: String, kind: CacheAssetKind? = null) = runOperation {
        val result = cacheRepository.clear(
            CacheClearRequest(
                workIds = setOf(workId),
                assetKinds = kind?.takeUnless { it == CacheAssetKind.WORK }?.let(::setOf).orEmpty(),
            ),
        )
        "已清理 ${result.removedWorks} 部作品、${result.removedAssets} 项资源"
    }

    suspend fun resetLocalData(): Boolean = runCatching { localDataResetter.reset() }.isSuccess

    fun consumeMessage() {
        message.value = null
    }

    private fun updateAndTrim(update: suspend () -> Unit) = runOperation {
        update()
        val result = cacheRepository.trimToPolicy()
        "设置已保存；自动整理 ${result.removedWorks} 部作品"
    }

    private fun runOperation(block: suspend () -> String) {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            message.value = runCatching { block() }.getOrElse { "操作失败，请稍后重试" }
            busy.value = false
        }
    }
}

private data class CacheInteraction(
    val selected: Set<String>,
    val busy: Boolean,
    val filling: Boolean,
    val message: String?,
)
