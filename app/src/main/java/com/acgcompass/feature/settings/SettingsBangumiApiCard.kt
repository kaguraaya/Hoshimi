package com.acgcompass.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.FlatTextField
import com.acgcompass.core.designsystem.OptionList
import com.acgcompass.core.designsystem.PrismCard

// endregion

// region Bangumi API 地址

/**
 * Bangumi API 地址配置卡，使用单个下拉框选择官方、社区或自定义地址。
 * 下拉选项：官方 API（默认）/ bgmapi.anibt.net / api.bangumi.one / 自定义 API 地址。
 * 选「自定义」才显示输入框；非官方地址显示风险提示与 Token 同意开关（底层保护逻辑不变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BangumiApiConfigCard(state: BangumiApiUiState, actions: SettingsActions, modifier: Modifier = Modifier) {
    val officialOption = "官方 API（默认）"
    val customOption = "自定义 API 地址"
    // 选项：官方 + 候选 + 自定义。
    val options = buildList {
        add(officialOption)
        state.candidates.forEach { (label, _) -> add("$label（社区反代）") }
        add(customOption)
    }
    val currentLabel = when (state.mode) {
        BangumiApiMode.OFFICIAL -> officialOption
        BangumiApiMode.CUSTOM -> customOption
        BangumiApiMode.COMMUNITY ->
            state.candidates
                .firstOrNull { it.second.trimEnd('/') == state.baseUrl.trimEnd('/') }
                ?.let { "${it.first}（社区反代）" } ?: customOption
    }
    var customMode by remember(state.mode) { mutableStateOf(state.mode == BangumiApiMode.CUSTOM) }

    PrismCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BangumiApiHeader(state.baseUrl)
            OptionList(
                title = "API 地址",
                options = options,
                selected = if (customMode) customOption else currentLabel,
                label = { it },
                onSelect = { option ->
                    when (option) {
                        officialOption -> {
                            customMode = false
                            actions.onUseOfficialBangumiApi()
                        }
                        customOption -> customMode = true
                        else -> {
                            customMode = false
                            val index = options.indexOf(option) - 1
                            state.candidates.getOrNull(index)?.let {
                                actions.onSelectBangumiCandidate(it.second)
                            }
                        }
                    }
                },
            )
            if (customMode) {
                BangumiCustomUrlEditor(state.customDraft, actions)
            }
            if (!state.isOfficial) {
                BangumiProxyWarning(state.consent, actions.onBangumiConsentChange)
            }
        }
    }
}

@Composable
internal fun BangumiApiHeader(baseUrl: String) {
    Text("Bangumi API 地址", style = MaterialTheme.typography.titleMedium)
    Text(
        "当前：$baseUrl",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "官方地址在部分网络环境可能无法直连，可改用社区反代或自建反代。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun BangumiCustomUrlEditor(customDraft: String, actions: SettingsActions) {
    FlatTextField(
        value = customDraft,
        onValueChange = actions.onBangumiCustomUrlChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("自定义 API 地址") },
        placeholder = { Text("https://your-proxy.example.com/") },
    )
    Button(onClick = actions.onSaveBangumiCustomUrl, modifier = Modifier.fillMaxWidth()) {
        Text("保存自定义地址", maxLines = 1)
    }
}

@Composable
internal fun BangumiProxyWarning(consent: Boolean, onConsentChange: (Boolean) -> Unit) {
    FlatDivider()
    Text(
        "你正在使用非官方 Bangumi API 地址。个人 Token 会发送到该地址，请确认你信任该服务。" +
            "建议优先使用官方 API、自己的代理或自建反代。公共搜索可用非官方地址；个人同步需在下方确认后才生效。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    ToggleRow(
        label = "我已知晓并同意个人 Token 发往该非官方地址",
        description = "用于个人同步（getMe / 收藏）。不勾选则仅公共搜索使用该地址。",
        checked = consent,
        enabled = true,
        onCheckedChange = onConsentChange,
    )
}
