package com.acgcompass.feature.taste

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.ui.Cta
import com.acgcompass.core.ui.UiState
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.taste.AdvancedTasteProfile
import com.acgcompass.domain.taste.TagClassificationController
import com.acgcompass.domain.taste.TagClassificationResult
import com.acgcompass.domain.taste.TagClassifyProgress
import com.acgcompass.domain.taste.TagDimensionSummary
import com.acgcompass.domain.taste.TasteAnalysisController
import com.acgcompass.domain.taste.TasteImportResult
import com.acgcompass.domain.taste.TasteLocalAnalysisState
import com.acgcompass.domain.taste.TasteProcessingSummary
import com.acgcompass.domain.taste.TasteRefreshProgress
import com.acgcompass.domain.taste.TasteRefreshResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 口味画像 ViewModel。观察画像流，并提供从 Bangumi 同步和计算画像的入口。
 *
 * [onImportFromBangumi] 先用 [BangumiSyncManager] 同步收藏入库，再把入库的用户收藏映射为
 * [TasteInputRecord] 交 [TasteProfileRepository.importAndCompute] 计算并持久化，画像流随之刷新。
 */
@HiltViewModel
class TasteProfileViewModel @Inject constructor(
    private val repository: TasteProfileRepository,
    private val analysisController: TasteAnalysisController,
    private val tagClassificationController: TagClassificationController,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** 联网分析进度；null 表示当前没有分析任务。 */
    val refreshProgress: StateFlow<TasteRefreshProgress?> = analysisController.refreshProgress
    val localAnalysisState: StateFlow<TasteLocalAnalysisState> = analysisController.localAnalysisState

    val tagClassifyProgress: StateFlow<TagClassifyProgress?> = tagClassificationController.state
        .map { state ->
            state.takeIf { it.active && it.phase != null }?.let {
                TagClassifyProgress(
                    done = it.completed,
                    total = it.total,
                    batchIndex = it.batchIndex,
                    batchCount = it.batchCount,
                    phase = requireNotNull(it.phase),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 12 维引擎画像（各维度偏好 / 题材组合 / 样本量 / 覆盖率 / 均分），供画像页「更丰富」展示；null=尚未构建。 */
    val advancedProfile: StateFlow<AdvancedTasteProfile?> = analysisController.advancedProfile

    val classifying: StateFlow<Boolean> = tagClassificationController.state
        .map { it.active }
        .stateIn(viewModelScope, SharingStarted.Eagerly, tagClassificationController.state.value.active)

    val classificationState = tagClassificationController.state

    private val _pendingTagCount = MutableStateFlow<Int?>(null)
    val pendingTagCount: StateFlow<Int?> = _pendingTagCount.asStateFlow()

    /**
     * AI「升维」效果摘要（各精确维度已细化多少个原本兜底为「题材」的标签）。进入画像页与
     * 每次升维分类完成后刷新；为空（未配置 / 未分类）时页面隐藏该区。
     */
    private val _dimensionSummary = MutableStateFlow<TagDimensionSummary?>(null)
    val dimensionSummary: StateFlow<TagDimensionSummary?> = _dimensionSummary.asStateFlow()

    private val _processingSummary = MutableStateFlow<TasteProcessingSummary?>(null)
    val processingSummary: StateFlow<TasteProcessingSummary?> = _processingSummary.asStateFlow()

    init {
        viewModelScope.launch {
            advancedProfile
                .map { profile -> profile?.let { it.generatedAt to it.sampleCount } }
                .distinctUntilChanged()
                .collect { snapshot ->
                    if (snapshot != null) refreshPendingTagCount()
                    if (snapshot != null) refreshProcessingSummary()
                }
        }
        viewModelScope.launch {
            tagClassificationController.state
                .map { it.active to it.lastResult }
                .distinctUntilChanged()
                .collect { (active, result) ->
                    if (active || result == null) return@collect
                    refreshDimensionSummary()
                    refreshPendingTagCount()
                    refreshProcessingSummary()
                    _message.value = when (result) {
                        TagClassificationResult.COMPLETED -> {
                            val count = tagClassificationController.state.value.classified
                            if (count > 0) "已完成 $count 个 AI 标签升维分类" else "本轮没有新增有效分类"
                        }
                        TagClassificationResult.PARTIAL -> "部分标签请求失败，已保留进度，可稍后继续"
                        TagClassificationResult.NOTHING_TO_DO -> "没有待分类的新标签"
                        TagClassificationResult.NOT_CONFIGURED -> "未配置 AI，已继续使用本地规则"
                        TagClassificationResult.FAILED -> "AI 标签升维分类未完成，请检查模型或网络后重试"
                        TagClassificationResult.STOPPED -> "已停止；完成的批次已经保留"
                    }
                    tagClassificationController.acknowledgeLastResult()
                }
        }
    }

    /** 刷新升维效果摘要（后台读缓存计数；失败静默保持原值）。 */
    private fun refreshDimensionSummary() {
        viewModelScope.launch {
            runCatching { analysisController.dimensionSummary() }
                .onSuccess { _dimensionSummary.value = it }
        }
    }

    private fun refreshPendingTagCount() {
        viewModelScope.launch {
            _pendingTagCount.value = runCatching { analysisController.pendingTagCount() }.getOrDefault(0)
        }
    }

    private fun refreshProcessingSummary() {
        viewModelScope.launch {
            runCatching { analysisController.processingSummary() }
                .onSuccess { _processingSummary.value = it }
        }
    }

    val uiState: StateFlow<UiState<TasteProfile>> =
        repository.observeTasteProfile()
            .map<TasteProfile?, UiState<TasteProfile>> { profile ->
                if (profile == null) UiState.Empty(EMPTY_CTA) else UiState.Success(profile)
            }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(UiState.Empty(EMPTY_CTA))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000L),
                initialValue = UiState.Loading,
            )

    /** 是否已配置 Bangumi（决定 CTA 是导入还是跳设置）。 */
    suspend fun isBangumiConfigured(): Boolean = analysisController.isBangumiConfigured()

    /**
     * 从 Bangumi 同步并计算口味画像。
     * @return true 表示已发起导入（已配置）；false 表示未配置，调用方应跳转设置。
     */
    fun onImportFromBangumi(onNotConfigured: () -> Unit) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            try {
                when (val result = analysisController.importFromBangumi()) {
                    is TasteImportResult.Completed -> {
                        refreshPendingTagCount()
                        refreshProcessingSummary()
                        refreshDimensionSummary()
                        _message.value = "已导入 ${result.importedCount} 条记录并刷新口味画像"
                    }
                    is TasteImportResult.SyncFailed ->
                        _message.value = result.message?.let { "导入失败：$it" } ?: "导入失败，请稍后再试"
                    TasteImportResult.NotConfigured -> onNotConfigured()
                    TasteImportResult.NoData -> _message.value = "Bangumi 暂无可用于画像的收藏 / 评分数据"
                    TasteImportResult.AnalysisFailed -> _message.value = "画像计算失败，请稍后再试"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _message.value = "导入失败，请稍后再试"
            } finally {
                _importing.value = false
            }
        }
    }

    /**
     * 手动「重新分析」入口。任务进度由持久化的 [localAnalysisState] 驱动。
     */
    fun onRefreshAnalysis() = runRefresh(isAuto = false)

    /**
     * 进入画像页时仅在输入指纹变化后自动联网分析；存在评分样本时在后台补齐特征并重算。
     * 失败保持静默，不阻塞页面。
     */
    fun onScreenOpened() {
        refreshDimensionSummary()
        refreshProcessingSummary()
        if (_importing.value) return
        viewModelScope.launch {
            runCatching { analysisController.ensureCurrentProfile() }
            refreshPendingTagCount()
            refreshProcessingSummary()
            refreshDimensionSummary()
        }
    }

    /** 补齐基础标签并重算本地画像；自动触发时只显示进度，不弹出提示。 */
    private fun runRefresh(isAuto: Boolean) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            try {
                when (analysisController.refresh()) {
                    TasteRefreshResult.COMPLETED -> {
                        refreshPendingTagCount()
                        refreshProcessingSummary()
                        refreshDimensionSummary()
                        if (!isAuto) _message.value = "已重新分析口味画像"
                    }
                    TasteRefreshResult.FAILED -> if (!isAuto) _message.value = "重新分析失败，请稍后再试"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (!isAuto) _message.value = "重新分析失败，请稍后再试"
            } finally {
                _importing.value = false
            }
        }
    }

    /**
     * 手动触发「AI 标签升维分类」——把本地兜底为题材的未知社区标签分批交 AI 归入更精确维度并缓存，
     * 供画像 / 评分复用。后台执行，进度经 [tagClassifyProgress] 展示；成功后仅用缓存快速重建画像使新维度生效。
     * AI 未配置 / 失败时提示并回退本地规则（不阻塞、不伪造）。
     */
    fun onClassifyTags() {
        if (localAnalysisState.value.isRunning) return
        tagClassificationController.start()
    }

    fun onStopClassifyingTags() = tagClassificationController.stop()

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        val EMPTY_CTA = Cta(label = "从 Bangumi 导入口味数据（或先在设置登录）", action = "import")
    }
}
