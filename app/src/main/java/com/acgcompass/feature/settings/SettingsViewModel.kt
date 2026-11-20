package com.acgcompass.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.ui.UiState
import com.acgcompass.data.credential.CredentialStatus
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SecretBundle
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.datastore.SettingsState
import com.acgcompass.data.datastore.ThemeMode
import com.acgcompass.domain.connectivity.ConnectionTarget
import com.acgcompass.domain.connectivity.ConnectionTester
import com.acgcompass.domain.model.ThemePreset
import com.acgcompass.domain.repository.BackupRepository
import com.acgcompass.domain.repository.CsvKind
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 ViewModel。使用 Hilt 注入依赖，并以 StateFlow 暴露状态。
 *
 * 职责：
 * - 把 [CredentialStore.observeStatus]（非敏感元数据）与 [SettingsDataStore]（开关）合并为
 *   [SettingsUiState]，对外暴露 `StateFlow<UiState<SettingsUiState>>`。
 * - 管理凭据输入草稿（仅内存）、临时显示（超时自动复位）、展开/折叠等纯 UI 状态。
 * - 保存 → [CredentialStore.put]；清除 → [CredentialStore.clear]；开关 → [SettingsDataStore]。
 * - 连接测试委托 [ConnectionTester] 提供真实探针。
 *
 * **安全不变式**：已保存的明文凭据**绝不**回读到 UI；保存后立即清空草稿。任何日志均不打印字段值。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val credentialStore: CredentialStore,
    private val settingsDataStore: SettingsDataStore,
    private val connectionTester: ConnectionTester,
    private val backupRepository: BackupRepository,
) : ViewModel() {

    /** 纯 UI 可编辑状态（草稿/展开/临时显示/测试态），不持久化。 */
    private val editState = MutableStateFlow(
        SourceDescriptors.ALL.associate { it.sourceId to SourceEditState() },
    )

    /** AI Provider 卡片与隐私卡片的纯 UI 本地状态（不持久化）。 */
    private val localUi = MutableStateFlow(LocalUiState())

    /** 保存后「凭据仅保存在本机」提示的可见性。 */
    private val saveNotice = MutableStateFlow(false)

    /** 备份、导入和 CSV 等数据操作的结果提示；`null` 表示无提示。 */
    private val _dataOpMessage = MutableStateFlow<String?>(null)
    val dataOpMessage: StateFlow<String?> = _dataOpMessage

    /** 清除一次性数据操作提示。 */
    fun clearDataOpMessage() {
        _dataOpMessage.value = null
    }

    /**
     * 构建备份 JSON（默认零凭据）。供 UI 取得文本后写入用户选定的文件（SAF）。
     * @return 备份 JSON；失败返回 `null` 并设置提示。
     */
    suspend fun writeBackup(output: java.io.OutputStream): Boolean =
        when (backupRepository.exportBackupTo(output, includeCredentials = false)) {
            is AppResult.Success -> true
            is AppResult.Failure -> {
                _dataOpMessage.value = "导出失败，请重试"
                false
            }
        }

    /** 构建指定表的 CSV 文本。 */
    suspend fun buildCsv(kind: CsvKind): String? = when (val r = backupRepository.exportCsv(kind)) {
        is AppResult.Success -> r.data
        is AppResult.Failure -> {
            _dataOpMessage.value = "CSV 导出失败，请重试"
            null
        }
    }

    /** 导入备份 JSON（合并不覆盖，冲突回报）。结果以提示展示，不静默覆盖。 */
    fun importBackup(open: () -> java.io.InputStream?) {
        viewModelScope.launch {
            val result = runCatchingCancellable {
                withContext(Dispatchers.IO) { open()?.buffered()?.use { backupRepository.importBackupFrom(it) } }
            }.getOrNull()
            when (result) {
                is AppResult.Success -> {
                    val rep = result.data
                    val conflictKept = rep.keptLocalCount
                    _dataOpMessage.value =
                        "导入完成：新增 ${rep.added}，更新 ${rep.updated}，冲突 ${rep.conflictCount}" +
                        if (conflictKept > 0) "（其中 $conflictKept 项保留本地较新数据，未覆盖）" else ""
                }
                else -> _dataOpMessage.value = "导入失败：备份文件可能损坏或格式不符"
            }
        }
    }

    /** 导出成功提示（写文件由 UI 完成后调用）。 */
    fun notifyExported(label: String) {
        _dataOpMessage.value = "$label 已导出"
    }

    fun notifyExportFailed(label: String) {
        _dataOpMessage.value = "$label 写入失败，请重试"
    }

    fun notifyNoExportData(label: String) {
        _dataOpMessage.value = "当前没有可导出的记录：$label"
    }

    val uiState: StateFlow<UiState<SettingsUiState>> =
        combine(
            editState,
            credentialStore.observeStatus(),
            combine(settingsDataStore.settings, settingsDataStore.bangumiUsername) { s, u -> s to u },
            saveNotice,
            localUi,
        ) { edits, statusMap, settingsAndUser, notice, local ->
            val (settings, bangumiUsername) = settingsAndUser
            val cards = SourceDescriptors.ALL.map { descriptor ->
                val card = descriptor.toCardState(
                    edit = edits[descriptor.sourceId] ?: SourceEditState(),
                    status = statusMap[descriptor.sourceId],
                    settings = settings,
                )
                // Bangumi 卡注入实际用户名（登录/同步后从 /v0/me 持久化），不再恒显「暂无数据」。
                if (descriptor.sourceId == SourceId.BANGUMI) card.copy(username = bangumiUsername) else card
            }
            UiState.Success(
                SettingsUiState(
                    cards = cards,
                    aiProvider = buildAiProviderCard(local.ai, statusMap[SourceId.AI_PROVIDER], settings),
                    privacy = PrivacyCardState(
                        expanded = local.privacyExpanded,
                        allowAiAnalyzeReviews = settings.allowAiAnalyzeReviews,
                        recordTimeMachineSnapshots = settings.recordTimeMachineSnapshots,
                        autoSyncIntervalMinutes = settings.autoSyncIntervalMinutes,
                        recommendMinCommunityScore = settings.recommendMinCommunityScore,
                        tasteMatchThreshold = settings.tasteMatchThreshold,
                        scoreDiffAutoBackfill = settings.scoreDiffAutoBackfill,
                        syncBacklogToBangumi = settings.syncBacklogToBangumi,
                        aiDailyDraw = settings.aiDailyDraw,
                        searchHistoryEnabled = settings.searchHistoryEnabled,
                        aiConfigured = statusMap[SourceId.AI_PROVIDER]?.configured == true,
                        tasteMatchBranch = settings.tasteMatchBranch,
                    ),
                    saveNoticeVisible = notice,
                    bangumiApi = buildBangumiApiState(settings, local),
                    autoSyncIntervalMinutes = settings.autoSyncIntervalMinutes,
                    themeMode = settings.themeMode,
                    dynamicColor = settings.dynamicColor,
                    lightThemePreset = settings.lightThemePreset,
                    darkThemePreset = settings.darkThemePreset,
                    predictiveBackEnabled = settings.predictiveBackEnabled,
                ),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = UiState.Loading,
        )

    init {
        // 恢复已保存的 AI provider 类型与非敏感配置（Base URL / 模型名），
        // 使设置页重新进入时仍展示用户上次的选择与端点（API Key 仍以掩码占位，绝不回读明文）。
        viewModelScope.launch {
            val persisted = runCatchingCancellable { settingsDataStore.aiProviderId.first() }.getOrNull()
            val option = aiProviderOptionFromProviderId(persisted)
            val saved = runCatchingCancellable { credentialStore.get(SourceId.AI_PROVIDER) }.getOrNull()
            localUi.update { state ->
                val drafts = state.ai.drafts.toMutableMap()
                saved?.baseUrl?.takeIf { it.isNotBlank() }?.let { drafts[CredentialFieldKey.BASE_URL] = it }
                saved?.model?.takeIf { it.isNotBlank() }?.let { drafts[CredentialFieldKey.MODEL] = it }
                state.copy(
                    ai = state.ai.copy(
                        selectedProvider = option ?: state.ai.selectedProvider,
                        drafts = drafts,
                    ),
                )
            }
        }
    }

    // region UI 交互

    /** 展开 / 折叠某源卡片。 */
    fun onToggleExpand(source: SourceId) = mutateEdit(source) { it.copy(expanded = !it.expanded) }

    /** 凭据输入框内容变更（草稿，仅内存）。 */
    fun onFieldChange(source: SourceId, key: CredentialFieldKey, value: String) =
        mutateEdit(source) { it.copy(drafts = it.drafts + (key to value)) }

    /** 切换某字段的临时显示；开启后 [REVEAL_TIMEOUT_MS] 毫秒自动复位为掩码。 */
    fun onToggleReveal(source: SourceId, key: CredentialFieldKey) {
        val edit = editState.value[source]
        val nowRevealed = edit?.revealed?.contains(key) == true
        // 仅当存在新输入草稿时才允许临时显示；已保存的掩码值绝不回显明文。
        if (!nowRevealed && edit?.drafts?.get(key).isNullOrBlank()) return
        mutateEdit(source) {
            val next = if (nowRevealed) it.revealed - key else it.revealed + key
            it.copy(revealed = next)
        }
        if (!nowRevealed) {
            viewModelScope.launch {
                delay(REVEAL_TIMEOUT_MS)
                mutateEdit(source) { it.copy(revealed = it.revealed - key) }
            }
        }
    }

    /** 保存某源凭据到加密存储，并清空草稿；展示「仅保存在本机」提示。 */
    fun onSave(source: SourceId) {
        val edit = editState.value[source] ?: return
        val tokenDraft = edit.drafts[CredentialFieldKey.TOKEN]?.trim()?.takeIf { it.isNotEmpty() }
        val clientIdDraft = edit.drafts[CredentialFieldKey.CLIENT_ID]?.trim()?.takeIf { it.isNotEmpty() }
        val clientSecretDraft = edit.drafts[CredentialFieldKey.CLIENT_SECRET]?.trim()?.takeIf { it.isNotEmpty() }
        if (tokenDraft == null && clientIdDraft == null && clientSecretDraft == null) return
        viewModelScope.launch {
            // 与已存凭据合并，仅覆盖本次填写的字段，避免未编辑的字段被清空。
            val existing = runCatchingCancellable { credentialStore.get(source) }.getOrNull()
            val merged = SecretBundle(
                token = tokenDraft ?: existing?.token,
                clientId = clientIdDraft ?: existing?.clientId,
                clientSecret = clientSecretDraft ?: existing?.clientSecret,
                baseUrl = existing?.baseUrl,
                model = existing?.model,
                // OAuth 续期字段仅由授权流程写入。用户**手动**改写 Token 时清除（手动 PAT 无 refresh_token，
                // 留着会导致启动续期失败）；仅改 client_id/secret 等时保留，避免已登录态丢失。
                refreshToken = if (tokenDraft != null) null else existing?.refreshToken,
                tokenExpiresAt = if (tokenDraft != null) null else existing?.tokenExpiresAt,
            )
            if (!merged.hasAnyValue()) return@launch
            credentialStore.put(source, merged)
            // 保存后立即清空草稿与临时显示，明文不在 UI 残留。
            mutateEdit(source) { it.copy(drafts = emptyMap(), revealed = emptySet()) }
            saveNotice.value = true
        }
    }

    /** 清除某源凭据（含 Bangumi「清除授权」），并重置草稿与测试态。 */
    fun onClear(source: SourceId) {
        viewModelScope.launch {
            credentialStore.clear(source)
            if (source == SourceId.BANGUMI) {
                settingsDataStore.setBangumiUsername("")
            }
            mutateEdit(source) {
                it.copy(drafts = emptyMap(), revealed = emptySet(), test = ConnectionTestState())
            }
        }
    }

    /** 切换源相关开关，写入 [SettingsDataStore]。 */
    @Suppress("CyclomaticComplexMethod") // 每个开关一个分支
    fun onToggleChange(toggleKey: String, checked: Boolean) {
        viewModelScope.launch {
            when (toggleKey) {
                ToggleKeys.BANGUMI_ENABLED -> settingsDataStore.setBangumiEnabled(checked)
                ToggleKeys.ANILIST_ENABLED -> settingsDataStore.setAnilistEnabled(checked)
                ToggleKeys.JIKAN_ENABLED -> settingsDataStore.setJikanEnabled(checked)
                ToggleKeys.MAL_ENABLED -> settingsDataStore.setMalEnabled(checked)
                ToggleKeys.VNDB_ENABLED -> settingsDataStore.setVndbEnabled(checked)
                ToggleKeys.SHOW_ADULT_CONTENT -> settingsDataStore.setShowAdultContent(checked)
                ToggleKeys.AUTO_TRANSLATE_VNDB_DESCRIPTION ->
                    settingsDataStore.setAutoTranslateVndbDescription(checked)
                ToggleKeys.ALLOW_AI_ANALYZE_REVIEWS -> settingsDataStore.setAllowAiAnalyzeReviews(checked)
                ToggleKeys.RECORD_TIME_MACHINE_SNAPSHOTS -> settingsDataStore.setRecordTimeMachineSnapshots(checked)
                ToggleKeys.SEARCH_HISTORY -> {
                    settingsDataStore.setSearchHistoryEnabled(checked)
                    // 关掉时把已有记录一起清掉。
                    if (!checked) settingsDataStore.clearSearchHistory()
                }
                ToggleKeys.SCORE_DIFF_AUTO_BACKFILL -> settingsDataStore.setScoreDiffAutoBackfill(checked)
                ToggleKeys.AI_DAILY_DRAW -> settingsDataStore.setAiDailyDraw(checked)
                ToggleKeys.SYNC_BACKLOG_TO_BANGUMI -> settingsDataStore.setSyncBacklogToBangumi(checked)
                ToggleKeys.AI_THINKING_ENABLED -> settingsDataStore.setAiThinkingEnabled(
                    localUi.value.ai.selectedProvider.toProviderStorageId(),
                    checked,
                )
            }
        }
    }

    /** 触发连接测试（委托 [ConnectionTester] 实现真实探针）。 */
    fun onTestConnection(source: SourceId) {
        mutateEdit(source) {
            it.copy(test = ConnectionTestState(status = ConnectionTestStatus.TESTING))
        }
        viewModelScope.launch {
            val result = connectionTester.test(source.toConnectionTarget())
            mutateEdit(source) {
                it.copy(
                    test = ConnectionTestState(
                        status = if (result.success) ConnectionTestStatus.SUCCESS else ConnectionTestStatus.FAILED,
                        message = result.message,
                        docUrl = result.docUrl,
                    ),
                )
            }
        }
    }

    /** 关闭「凭据仅保存在本机」提示。 */
    fun onDismissSaveNotice() {
        saveNotice.value = false
    }

    // endregion

    // region AI Provider

    /** 展开 / 折叠 AI Provider 卡片。 */
    fun onToggleAiExpand() = localUi.update { it.copy(ai = it.ai.copy(expanded = !it.ai.expanded)) }

    /** 选择 AI Provider 预设；自动以该预设的默认 Base URL / 模型名作为草稿建议（用户可改）。 */
    fun onAiProviderSelect(provider: AiProviderOption) {
        localUi.update { state ->
            val drafts = state.ai.drafts.toMutableMap()
            drafts[CredentialFieldKey.BASE_URL] = provider.defaultBaseUrl
            drafts[CredentialFieldKey.MODEL] = provider.defaultModel
            state.copy(ai = state.ai.copy(selectedProvider = provider, drafts = drafts))
        }
        // 持久化 provider 类型，使对应协议在调用时生效。
        viewModelScope.launch { settingsDataStore.setAiProviderId(provider.toProviderStorageId()) }
    }

    /** AI Provider 输入框内容变更（草稿，仅内存）。 */
    fun onAiFieldChange(key: CredentialFieldKey, value: String) {
        localUi.update { it.copy(ai = it.ai.copy(drafts = it.ai.drafts + (key to value))) }
    }

    /** 切换 AI Provider 字段的临时显示；开启后超时自动复位为掩码。 */
    fun onToggleAiReveal(key: CredentialFieldKey) {
        val nowRevealed = localUi.value.ai.revealed.contains(key)
        // 仅当存在新输入草稿时才允许临时显示；已保存的掩码值绝不回显明文。
        if (!nowRevealed && localUi.value.ai.drafts[key].isNullOrBlank()) return
        localUi.update {
            val next = if (nowRevealed) it.ai.revealed - key else it.ai.revealed + key
            it.copy(ai = it.ai.copy(revealed = next))
        }
        if (!nowRevealed) {
            viewModelScope.launch {
                delay(REVEAL_TIMEOUT_MS)
                localUi.update { it.copy(ai = it.ai.copy(revealed = it.ai.revealed - key)) }
            }
        }
    }

    /** 保存 AI Provider 配置（API key + Base URL + 模型名），保存后清空敏感草稿并提示。 */
    fun onAiSave() {
        val ai = localUi.value.ai
        val apiKeyDraft = ai.drafts[CredentialFieldKey.API_KEY]?.trim()?.takeIf { it.isNotEmpty() }
        val baseUrlDraft = ai.drafts[CredentialFieldKey.BASE_URL]?.trim()?.takeIf { it.isNotEmpty() }
        val modelDraft = ai.drafts[CredentialFieldKey.MODEL]?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            // 与已存凭据合并，仅覆盖本次填写的字段；未编辑的字段沿用旧值。
            val existing = runCatchingCancellable { credentialStore.get(SourceId.AI_PROVIDER) }.getOrNull()
            val merged = SecretBundle(
                apiKey = apiKeyDraft ?: existing?.apiKey,
                baseUrl = baseUrlDraft ?: existing?.baseUrl,
                model = modelDraft ?: existing?.model,
            )
            if (!merged.hasAnyValue()) return@launch
            credentialStore.put(SourceId.AI_PROVIDER, merged)
            // 保存凭据时一并持久化当前 provider 类型。
            settingsDataStore.setAiProviderId(ai.selectedProvider.toProviderStorageId())
            // 仅清除敏感的 API Key 草稿与临时显示；保留 Base URL 和模型名。
            localUi.update {
                val keep = it.ai.drafts.filterKeys { k ->
                    k == CredentialFieldKey.BASE_URL || k == CredentialFieldKey.MODEL
                }
                it.copy(ai = it.ai.copy(drafts = keep, revealed = emptySet()))
            }
            saveNotice.value = true
        }
    }

    /** 清除 AI Provider 配置，并重置草稿与测试态。 */
    fun onAiClear() {
        viewModelScope.launch {
            credentialStore.clear(SourceId.AI_PROVIDER)
            localUi.update {
                it.copy(ai = it.ai.copy(drafts = emptyMap(), revealed = emptySet(), test = ConnectionTestState()))
            }
        }
    }

    /** 触发 AI Provider 连接测试（委托 [ConnectionTester]，三态结果）。 */
    fun onAiTestConnection() {
        localUi.update { it.copy(ai = it.ai.copy(test = ConnectionTestState(status = ConnectionTestStatus.TESTING))) }
        viewModelScope.launch {
            val result = connectionTester.test(ConnectionTarget.AI_PROVIDER)
            localUi.update {
                it.copy(
                    ai = it.ai.copy(
                        test = ConnectionTestState(
                            status = if (result.success) ConnectionTestStatus.SUCCESS else ConnectionTestStatus.FAILED,
                            message = result.message,
                            docUrl = result.docUrl,
                        ),
                    ),
                )
            }
        }
    }

    // endregion

    // region 隐私

    /** 展开 / 折叠隐私控制卡片。 */
    fun onTogglePrivacyExpand() = localUi.update { it.copy(privacyExpanded = !it.privacyExpanded) }

    /** 从数据管理入口进入设置时强制展开隐私控制卡片。 */
    fun expandPrivacy() = localUi.update { it.copy(privacyExpanded = true) }

    // endregion

    // region Bangumi API 地址

    private fun buildBangumiApiState(settings: SettingsState, local: LocalUiState): BangumiApiUiState {
        val url = settings.bangumiApiBaseUrl
        val official = settings.isBangumiOfficialApi
        val mode = when {
            official -> BangumiApiMode.OFFICIAL
            BANGUMI_API_CANDIDATES.any { it.second.trimEnd('/') == url.trimEnd('/') } -> BangumiApiMode.COMMUNITY
            else -> BangumiApiMode.CUSTOM
        }
        return BangumiApiUiState(
            mode = mode,
            baseUrl = url,
            customDraft = local.bangumiCustomDraft,
            consent = settings.bangumiNonOfficialTokenConsent,
            isOfficial = official,
        )
    }

    /** 切回官方 Bangumi API。 */
    fun onUseOfficialBangumiApi() {
        viewModelScope.launch { settingsDataStore.setBangumiApiBaseUrl(SettingsState.DEFAULT_BANGUMI_API_BASE_URL) }
    }

    /** 选择某个社区反代候选作为 Bangumi API 地址。 */
    fun onSelectBangumiCandidate(url: String) {
        viewModelScope.launch { settingsDataStore.setBangumiApiBaseUrl(url) }
    }

    /** 自定义 Bangumi API 地址输入草稿变更。 */
    fun onBangumiCustomUrlChange(draft: String) {
        localUi.update { it.copy(bangumiCustomDraft = draft) }
    }

    /** 保存自定义 Bangumi API 地址（格式校验 + 归一化由 DataStore 处理）。 */
    fun onSaveBangumiCustomUrl() {
        val draft = localUi.value.bangumiCustomDraft.trim()
        if (!draft.startsWith("http://") && !draft.startsWith("https://")) {
            _dataOpMessage.value = "API 地址格式错误：必须以 http:// 或 https:// 开头"
            return
        }
        viewModelScope.launch {
            settingsDataStore.setBangumiApiBaseUrl(draft)
            _dataOpMessage.value = "已保存自定义 Bangumi API 地址"
        }
    }

    /** 确认或取消「非官方 API + 个人 Token」隐私风险。 */
    fun onBangumiConsentChange(consented: Boolean) {
        viewModelScope.launch { settingsDataStore.setBangumiNonOfficialTokenConsent(consented) }
    }

    /** 设置自动同步间隔（分钟，0=关闭）。写入后由 Application 观察并即时重新调度。 */
    fun onSetAutoSyncInterval(minutes: Int) {
        viewModelScope.launch { settingsDataStore.setAutoSyncIntervalMinutes(minutes) }
    }

    /** 设置推荐社区均分下限（0~10，0=不限）。 */
    fun onSetRecommendMinScore(score: Float) {
        viewModelScope.launch { settingsDataStore.setRecommendMinCommunityScore(score) }
    }

    /** 选择口味匹配度分支（常规 / 保守 / 激进）；口味引擎会随之重算。 */
    fun onSetTasteMatchBranch(key: String) {
        viewModelScope.launch { settingsDataStore.setTasteMatchBranch(key) }
    }

    /** 设置口味匹配度阈值（0~1，0=关闭）。低于此匹配度的作品不进入推荐。 */
    fun onSetTasteMatchThreshold(threshold: Float) {
        viewModelScope.launch { settingsDataStore.setTasteMatchThreshold(threshold) }
    }

    fun onSetThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsDataStore.setThemeMode(mode) }
    }

    fun onSetDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setDynamicColor(enabled) }
    }

    fun onSetLightThemePreset(preset: ThemePreset) {
        viewModelScope.launch { settingsDataStore.setLightThemePreset(preset) }
    }

    fun onSetDarkThemePreset(preset: ThemePreset) {
        viewModelScope.launch { settingsDataStore.setDarkThemePreset(preset) }
    }

    fun onSetPredictiveBackEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setPredictiveBackEnabled(enabled) }
    }

    fun onSetAiTagClassificationMax(value: Int) {
        viewModelScope.launch { settingsDataStore.setAiTagClassificationMax(value) }
    }

    // endregion

    private fun buildAiProviderCard(
        ai: AiEditState,
        status: CredentialStatus?,
        settings: SettingsState,
    ): AiProviderCardState {
        val configured = status?.configured == true
        val apiKeyDraft = ai.drafts[CredentialFieldKey.API_KEY] ?: ""
        val fields = listOf(
            CredentialFieldState(
                key = CredentialFieldKey.API_KEY,
                label = "API Key",
                sensitive = true,
                value = apiKeyDraft,
                revealed = ai.revealed.contains(CredentialFieldKey.API_KEY),
                placeholder = "粘贴你在所选服务申请的 API Key",
                // 已保存且无新草稿时仅显示掩码，禁止临时显示。
                savedMasked = configured && apiKeyDraft.isBlank(),
            ),
            CredentialFieldState(
                key = CredentialFieldKey.BASE_URL,
                label = "Base URL",
                sensitive = false,
                value = ai.drafts[CredentialFieldKey.BASE_URL] ?: ai.selectedProvider.defaultBaseUrl,
                placeholder = "OpenAI 兼容端点，如 https://api.openai.com/v1",
            ),
            CredentialFieldState(
                key = CredentialFieldKey.MODEL,
                label = "模型名",
                sensitive = false,
                value = ai.drafts[CredentialFieldKey.MODEL] ?: ai.selectedProvider.defaultModel,
                placeholder = "如 gpt-5.2",
            ),
        )
        return AiProviderCardState(
            expanded = ai.expanded,
            configured = configured,
            statusText = when (status?.status) {
                CredentialStatus.Status.TEST_SUCCESS -> "连接正常"
                CredentialStatus.Status.TEST_FAILED -> "连接失败"
                else -> if (configured) "已配置" else "未配置"
            },
            lastTestedAtText = status?.lastTestedAt?.let { TIME_FORMATTER.format(Instant.ofEpochMilli(it)) },
            selectedProvider = ai.selectedProvider,
            fields = fields,
            docUrl = ai.selectedProvider.docUrl,
            budgetReminder = "AI 调用会消耗你所配置服务的额度并可能产生费用，请自行关注用量与预算。",
            test = ai.test,
            // 任一字段有草稿即可保存；合并保存不会清空未编辑的凭据。
            hasDraft = ai.drafts.values.any { it.isNotBlank() },
            thinkingEnabled = ai.selectedProvider.toProviderStorageId() in settings.aiThinkingEnabledProviders,
            maxClassificationTags = settings.aiTagClassificationMax,
        )
    }

    private inline fun mutateEdit(source: SourceId, crossinline transform: (SourceEditState) -> SourceEditState) {
        editState.update { map ->
            val current = map[source] ?: SourceEditState()
            map + (source to transform(current))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val REVEAL_TIMEOUT_MS = 15_000L
    }
}

/** 单源纯 UI 可编辑状态。 */
private data class SourceEditState(
    val expanded: Boolean = false,
    val drafts: Map<CredentialFieldKey, String> = emptyMap(),
    val revealed: Set<CredentialFieldKey> = emptySet(),
    val test: ConnectionTestState = ConnectionTestState(),
)

/** AI Provider 卡片纯 UI 可编辑状态（不持久化）。 */
private data class AiEditState(
    val expanded: Boolean = false,
    val selectedProvider: AiProviderOption = AiProviderOption.DEEPSEEK,
    val drafts: Map<CredentialFieldKey, String> = emptyMap(),
    val revealed: Set<CredentialFieldKey> = emptySet(),
    val test: ConnectionTestState = ConnectionTestState(),
)

/** AI Provider 与隐私卡片的本地 UI 状态聚合（合并为单个 flow，避免 combine 元数超限）。 */
private data class LocalUiState(
    val ai: AiEditState = AiEditState(),
    val privacyExpanded: Boolean = false,
    /** Bangumi 自定义 API 地址输入草稿。 */
    val bangumiCustomDraft: String = "",
)

/** 源相关开关键常量。 */
internal object ToggleKeys {
    const val BANGUMI_ENABLED = "bangumi_enabled"
    const val ANILIST_ENABLED = "anilist_enabled"
    const val JIKAN_ENABLED = "jikan_enabled"
    const val MAL_ENABLED = "mal_enabled"
    const val VNDB_ENABLED = "vndb_enabled"
    const val SHOW_ADULT_CONTENT = "show_adult_content"
    const val AUTO_TRANSLATE_VNDB_DESCRIPTION = "auto_translate_vndb_description"
    const val ALLOW_AI_ANALYZE_REVIEWS = "allow_ai_analyze_reviews"
    const val RECORD_TIME_MACHINE_SNAPSHOTS = "record_time_machine_snapshots"
    const val SEARCH_HISTORY = "search_history"
    const val SCORE_DIFF_AUTO_BACKFILL = "score_diff_auto_backfill"
    const val AI_DAILY_DRAW = "ai_daily_draw"
    const val SYNC_BACKLOG_TO_BANGUMI = "sync_backlog_to_bangumi"
    const val AI_THINKING_ENABLED = "ai_thinking_enabled"
}

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** Stable provider identifier persisted by the settings layer. */
private fun AiProviderOption.toProviderStorageId(): String = when (this) {
    AiProviderOption.OPENAI -> "OPENAI"
    AiProviderOption.GEMINI -> "GEMINI"
    AiProviderOption.DEEPSEEK -> "DEEPSEEK"
    AiProviderOption.OPENROUTER -> "OPENROUTER"
    AiProviderOption.CUSTOM -> "CUSTOM_OPENAI_COMPAT"
}

/** 将持久化的提供商标识映射为 UI 选项；未知或空值返回 `null`。 */
private fun aiProviderOptionFromProviderId(raw: String?): AiProviderOption? = when (raw?.trim()?.uppercase()) {
    "OPENAI" -> AiProviderOption.OPENAI
    "GEMINI" -> AiProviderOption.GEMINI
    "DEEPSEEK" -> AiProviderOption.DEEPSEEK
    "OPENROUTER" -> AiProviderOption.OPENROUTER
    "CUSTOM_OPENAI_COMPAT" -> AiProviderOption.CUSTOM
    else -> null
}

private fun SourceId.toConnectionTarget(): ConnectionTarget = when (this) {
    SourceId.JIKAN -> ConnectionTarget.TENRAI
    else -> ConnectionTarget.valueOf(name)
}

/**
 * 静态源卡片描述：标题、文档、字段定义、开关定义、提示信息。
 * 与运行时可变状态分离，运行时通过 [toCardState] 合并出 [SourceCardState]。
 */
private data class SourceDescriptor(
    val sourceId: SourceId,
    val title: String,
    val docUrl: String,
    val requiresCredential: Boolean,
    val fieldDefs: List<CredentialFieldState>,
    val toggleDefs: List<ToggleDef>,
    val infoNotes: List<String>,
    val showUsername: Boolean = false,
    /** 该源是否支持无凭据公共搜索；用于生成未配置状态的说明。 */
    val publicSearchAvailable: Boolean = false,
) {
    fun toCardState(edit: SourceEditState, status: CredentialStatus?, settings: SettingsState): SourceCardState {
        val configured = status?.configured == true
        val fields = fieldDefs.map { def ->
            val draft = edit.drafts[def.key] ?: ""
            def.copy(
                value = draft,
                revealed = edit.revealed.contains(def.key),
                // 已配置的敏感字段在没有新草稿时仅显示掩码。
                savedMasked = configured && def.sensitive && draft.isBlank(),
            )
        }
        val toggles = toggleDefs.map { def ->
            SourceToggleState(
                key = def.key,
                label = def.label,
                description = def.description,
                checked = def.checkedSelector(settings),
                // MAL 仅在显式配置后才允许启用。
                enabled = !def.requiresConfigured || configured,
            )
        }
        return SourceCardState(
            sourceId = sourceId,
            title = title,
            docUrl = docUrl,
            expanded = edit.expanded,
            configured = configured,
            statusText = statusText(configured, status?.status),
            lastTestedAtText = status?.lastTestedAt?.let { TIME_FORMATTER.format(Instant.ofEpochMilli(it)) },
            fields = fields,
            toggles = toggles,
            infoNotes = infoNotes,
            showUsername = showUsername,
            username = null,
            test = edit.test,
            hasDraft = fields.any { it.value.isNotBlank() },
        )
    }

    private fun statusText(configured: Boolean, rawStatus: String?): String {
        if (!requiresCredential) return "公共搜索可用，无需 API Key"
        return when (rawStatus) {
            CredentialStatus.Status.TEST_SUCCESS -> "连接正常"
            CredentialStatus.Status.TEST_FAILED -> "连接失败"
            else -> when {
                configured -> "已配置"
                // 公共可搜的源未配置 Token 时，仅个人同步不可用。
                publicSearchAvailable -> "公共搜索可用，个人同步未配置"
                else -> "未配置"
            }
        }
    }
}

/** 开关定义：携带从 [SettingsState] 读取当前值的选择器与是否需先配置。 */
private data class ToggleDef(
    val key: String,
    val label: String,
    val description: String?,
    val checkedSelector: (SettingsState) -> Boolean,
    val requiresConfigured: Boolean = false,
)

/**
 * 五个数据源的静态卡片定义（顺序为 Bangumi → AniList → Tenrai → MAL → VNDB）。
 * AI Provider 与隐私卡片由设置页中的独立区块呈现。
 */
private object SourceDescriptors {
    val ALL: List<SourceDescriptor> = listOf(
        // Bangumi：个人 Token、用户名、同步开关与构建级 OAuth 登录入口。
        SourceDescriptor(
            sourceId = SourceId.BANGUMI,
            title = "Bangumi",
            docUrl = SettingsDocLinks.BANGUMI,
            requiresCredential = true,
            fieldDefs = listOf(
                CredentialFieldState(
                    key = CredentialFieldKey.TOKEN,
                    // 可选标识由 UI 统一追加，字段标签本身不重复包含。
                    label = "Access Token（手动登录用）",
                    sensitive = true,
                    optional = true,
                    placeholder = "粘贴 Token 即可登录；或用下方「用 Bangumi 登录」",
                ),
            ),
            toggleDefs = listOf(
                ToggleDef(
                    key = ToggleKeys.BANGUMI_ENABLED,
                    label = "同步与启用",
                    description = "启用 Bangumi 作为数据源并同步收藏/评分",
                    checkedSelector = { it.bangumiEnabled },
                ),
            ),
            infoNotes = listOf(
                "若当前构建已配置 Bangumi OAuth 应用，可使用下方的一键登录；否则请使用个人 Access Token。" +
                    "OAuth 应用凭据仅在构建阶段注入，不在应用设置中填写。",
                "一键登录可通过构建配置的受限 OAuth 服务打开授权入口；登录和授权内容仍由 Bangumi 提供。",
                "想要长期有效的登录：可在 next.bgm.tv/demo/access-token 生成较长有效期的个人令牌，" +
                    "粘贴到上方 Access Token 即可（OAuth 登录的 token 约 7 天，靠每次打开应用自动续期）。",
            ),
            showUsername = true,
            publicSearchAvailable = true,
        ),
        // AniList：Token 或登录说明，以及列表读取开关。
        SourceDescriptor(
            sourceId = SourceId.ANILIST,
            title = "AniList",
            docUrl = SettingsDocLinks.ANILIST,
            requiresCredential = true,
            fieldDefs = listOf(
                CredentialFieldState(
                    key = CredentialFieldKey.TOKEN,
                    label = "Access Token",
                    sensitive = true,
                    optional = true,
                    placeholder = "公共查询免鉴权；填写 Token 可读取你的列表",
                ),
            ),
            toggleDefs = listOf(
                ToggleDef(
                    key = ToggleKeys.ANILIST_ENABLED,
                    label = "用户列表读取",
                    description = "启用后读取你的 AniList 列表数据",
                    checkedSelector = { it.anilistEnabled },
                ),
            ),
            infoNotes = listOf("公共查询无需 Token；如需读取个人列表，请在 AniList 生成 Access Token。"),
            publicSearchAvailable = true,
        ),
        // 兼容旧设置键；界面和业务来源均显示为 Tenrai。
        SourceDescriptor(
            sourceId = SourceId.JIKAN,
            title = "Tenrai",
            docUrl = SettingsDocLinks.TENRAI,
            requiresCredential = false,
            fieldDefs = emptyList(),
            toggleDefs = listOf(
                ToggleDef(
                    key = ToggleKeys.JIKAN_ENABLED,
                    label = "启用 Tenrai",
                    description = "MAL 公开评分补充，无需 API Key",
                    checkedSelector = { it.jikanEnabled },
                ),
            ),
            infoNotes = listOf(
                "仅补充评分；已有 Bangumi 条目时不会覆盖标题、简介、标签或封面。",
                "使用单并发和保守请求预算；服务异常时保留旧缓存。",
                "Jikan 已退出默认业务链路，仅保留独立诊断能力。",
            ),
        ),
        // MAL 官方：Client ID、可选 Secret 与 OAuth/PKCE 状态；仅显式配置后启用。
        SourceDescriptor(
            sourceId = SourceId.MAL,
            title = "MyAnimeList（官方）",
            docUrl = SettingsDocLinks.MAL,
            requiresCredential = true,
            fieldDefs = listOf(
                CredentialFieldState(
                    key = CredentialFieldKey.CLIENT_ID,
                    label = "Client ID",
                    sensitive = true,
                    placeholder = "你在 MAL 创建的应用 Client ID",
                ),
                CredentialFieldState(
                    key = CredentialFieldKey.CLIENT_SECRET,
                    label = "Client Secret",
                    sensitive = true,
                    optional = true,
                    placeholder = "如应用类型需要则填写",
                ),
            ),
            toggleDefs = listOf(
                ToggleDef(
                    key = ToggleKeys.MAL_ENABLED,
                    label = "启用 MAL 官方源",
                    description = "需先填写并保存 Client ID 后才能启用",
                    checkedSelector = { it.malEnabled },
                    requiresConfigured = true,
                ),
            ),
            infoNotes = listOf(
                "使用 OAuth2 PKCE 授权流程。",
                "请自行在 MAL 创建应用并填入 Client ID，不内置任何开发者私有信息。",
                "获取方式：登录 MyAnimeList → 账号设置 → API → Create ID，复制 Client ID。详见下方「查看官方文档」。",
            ),
        ),
        // VNDB：Token、成人内容显示开关与分级过滤。
        SourceDescriptor(
            sourceId = SourceId.VNDB,
            title = "VNDB",
            docUrl = SettingsDocLinks.VNDB,
            requiresCredential = true,
            fieldDefs = listOf(
                CredentialFieldState(
                    key = CredentialFieldKey.TOKEN,
                    label = "Token",
                    sensitive = true,
                    optional = true,
                    placeholder = "填写后可读取你的 VN 列表",
                ),
            ),
            toggleDefs = listOf(
                ToggleDef(
                    key = ToggleKeys.VNDB_ENABLED,
                    label = "启用 VNDB",
                    description = "Visual Novel 资料与评分",
                    checkedSelector = { it.vndbEnabled },
                ),
                ToggleDef(
                    key = ToggleKeys.SHOW_ADULT_CONTENT,
                    label = "显示成人内容",
                    description = "关闭时按分级过滤，默认关闭",
                    checkedSelector = { it.showAdultContent },
                ),
                ToggleDef(
                    key = ToggleKeys.AUTO_TRANSLATE_VNDB_DESCRIPTION,
                    label = "无中文简介时自动翻译",
                    description = "默认关闭；需配置 AI，译文按内容指纹缓存",
                    checkedSelector = { it.autoTranslateVndbDescription },
                ),
            ),
            infoNotes = listOf("默认按内容分级过滤；开启「显示成人内容」需自行确认所在地区合规。"),
            publicSearchAvailable = true,
        ),
    )
}
