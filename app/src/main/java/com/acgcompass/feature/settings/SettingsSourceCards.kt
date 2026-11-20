package com.acgcompass.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.FlatDivider
import com.acgcompass.core.designsystem.FlatSwitch
import com.acgcompass.core.designsystem.FlatTextField
import com.acgcompass.core.designsystem.OptionList
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.data.credential.SourceId
import com.acgcompass.data.datastore.SettingsState

/** Bangumi OAuth 登录入口卡片（设置 → Bangumi）。构建凭据完整时才开放登录。 */
@Composable
internal fun BangumiOAuthLoginCard(onLogin: () -> Unit) {
    val hasBuildCredentials =
        com.acgcompass.BuildConfig.BANGUMI_CLIENT_ID.isNotBlank() &&
            (
                com.acgcompass.BuildConfig.BANGUMI_CLIENT_SECRET.isNotBlank() ||
                    com.acgcompass.BuildConfig.BANGUMI_OAUTH_PROXY_BASE_URL.isNotBlank()
                )
    PrismCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "用 Bangumi 登录（OAuth）", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (hasBuildCredentials) {
                    "此构建已启用 Bangumi OAuth。登录后，应用会在令牌到期前尝试自动续期。"
                } else {
                    "当前构建未配置 Bangumi OAuth，一键登录不可用；" +
                        "仍可在上方保存个人 Access Token。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onLogin,
                modifier = Modifier.fillMaxWidth(),
                enabled = hasBuildCredentials,
            ) {
                Text("用 Bangumi 登录")
            }
        }
    }
}

// region 可复用基础块

/** 可折叠卡片骨架：标题行、独立状态行和展开内容。 */
@Composable
internal fun CollapsibleCard(
    title: String,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    statusText: String? = null,
    content: @Composable () -> Unit,
) {
    PrismCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            // 标题占满剩余宽度并单行省略，状态文字单独成行。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = if (expanded) "$title 已展开" else "$title 已折叠" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleExpand) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "折叠" else "展开",
                    )
                }
            }
            // 行2：状态文案（独立成行，可换行，不再用会挤压标题的等宽 Chip）。
            if (statusText != null) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * 凭据输入框：敏感字段默认掩码（[PasswordVisualTransformation]），尾部提供临时显示开关。
 * 非敏感字段（Base URL / 模型名）始终明文。
 */
@Composable
internal fun CredentialField(
    field: CredentialFieldState,
    onValueChange: (String) -> Unit,
    onToggleReveal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FlatTextField(
        value = field.value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(field.label + if (field.optional) "（可选）" else "") },
        placeholder = {
            // 已保存的敏感字段只显示掩码；重新输入即可更换。
            val ph = if (field.savedMasked) "已保存 ••••••（重新输入以更换）" else field.placeholder
            if (ph.isNotEmpty()) Text(ph)
        },
        singleLine = true,
        visualTransformation = if (field.sensitive && !field.revealed) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        trailingIcon = if (field.sensitive && !field.savedMasked) {
            {
                IconButton(onClick = onToggleReveal) {
                    Icon(
                        imageVector = if (field.revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (field.revealed) "隐藏 ${field.label}" else "临时显示 ${field.label}",
                    )
                }
            }
        } else {
            null
        },
    )
}

/** 单个开关项（数据源启用 / 同步 / 列表读取 / 成人内容 / 隐私开关等）。 */
@Composable
internal fun ToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FlatSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/**
 * 连接测试三态结果：
 * - TESTING → 进度指示 + 「测试中」。
 * - SUCCESS → 成功文案。
 * - FAILED → 失败原因 + 「查看文档」入口。
 * - IDLE → 不渲染。
 */
@Composable
internal fun ConnectionTestResultRow(
    test: ConnectionTestState,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (test.status) {
        ConnectionTestStatus.IDLE -> Unit
        ConnectionTestStatus.TESTING -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("连接测试中…", style = MaterialTheme.typography.bodyMedium)
        }

        ConnectionTestStatus.SUCCESS -> Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = test.message ?: "连接成功",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        ConnectionTestStatus.FAILED -> Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = test.message ?: "连接失败",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val docUrl = test.docUrl
            if (!docUrl.isNullOrEmpty()) {
                DocLinkButton(url = docUrl, onOpenDoc = onOpenDoc)
            }
        }
    }
}

/** 「查看官方文档」入口按钮。绝不携带任何 key/token。 */
@Composable
internal fun DocLinkButton(
    url: String,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "查看官方文档",
) {
    TextButton(onClick = { onOpenDoc(url) }, modifier = modifier) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            modifier = Modifier.padding(start = 6.dp),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

// endregion

// region 数据源卡片

/**
 * 单个数据源的可折叠设置卡片：用户名（Bangumi）、凭据字段（掩码 + 临时显示）、开关、提示信息、
 * 连接测试三态、保存 / 清除按钮和官方文档链接。
 */
@Composable
internal fun SourceSettingsCard(card: SourceCardState, actions: SettingsActions, modifier: Modifier = Modifier) {
    CollapsibleCard(
        title = card.title,
        statusText = card.statusText,
        expanded = card.expanded,
        onToggleExpand = { actions.onToggleExpand(card.sourceId) },
        modifier = modifier,
    ) {
        SourceSettingsFields(card = card, actions = actions)
        ConnectionTestResultRow(test = card.test, onOpenDoc = actions.onOpenDoc)
        FlatDivider()
        SourceSettingsActions(card = card, actions = actions)
        DocLinkButton(url = card.docUrl, onOpenDoc = actions.onOpenDoc)
    }
}

@Composable
internal fun SourceSettingsFields(card: SourceCardState, actions: SettingsActions) {
    if (card.showUsername) {
        Text(
            text = "当前用户名：" + (card.username ?: "暂无数据"),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    card.lastTestedAtText?.let {
        Text(
            text = "最后测试：$it",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    card.fields.forEach { field ->
        CredentialField(
            field = field,
            onValueChange = { actions.onFieldChange(card.sourceId, field.key, it) },
            onToggleReveal = { actions.onToggleReveal(card.sourceId, field.key) },
        )
    }
    card.toggles.forEach { toggle ->
        ToggleRow(
            label = toggle.label,
            description = toggle.description,
            checked = toggle.checked,
            enabled = toggle.enabled,
            onCheckedChange = { actions.onToggleChange(toggle.key, it) },
        )
    }
    card.infoNotes.forEach { note ->
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun SourceSettingsActions(card: SourceCardState, actions: SettingsActions) {
    if (card.fields.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { actions.onSave(card.sourceId) },
                enabled = card.hasDraft,
            ) {
                Text("保存")
            }
            FilledTonalButton(
                onClick = { actions.onTestConnection(card.sourceId) },
                enabled = card.test.status != ConnectionTestStatus.TESTING,
            ) {
                Text("连接测试")
            }
        }
        if (card.configured) {
            TextButton(onClick = { actions.onClear(card.sourceId) }) {
                Text(text = if (card.sourceId == SourceId.BANGUMI) "清除授权" else "清除")
            }
        }
    } else {
        FilledTonalButton(
            onClick = { actions.onTestConnection(card.sourceId) },
            enabled = card.test.status != ConnectionTestStatus.TESTING,
        ) {
            Text("连接测试")
        }
    }
}

// endregion

// region AI 提供商卡片

/**
 * AI Provider 设置卡片：Provider 选择、API key（掩码）、Base URL、模型名、连接测试、预算提醒、
 * 保存 / 清除 / 文档链接。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiProviderSettingsCard(
    card: AiProviderCardState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    CollapsibleCard(
        title = "AI 提供商",
        statusText = card.statusText,
        expanded = card.expanded,
        onToggleExpand = actions.onToggleAiExpand,
        modifier = modifier,
    ) {
        OptionList(
            title = "AI 提供商",
            options = card.options,
            selected = card.selectedProvider,
            label = { it.displayName },
            onSelect = actions.onAiProviderSelect,
        )

        card.lastTestedAtText?.let {
            Text(
                text = "最后测试：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // API key（掩码）+ Base URL + 模型名。
        card.fields.forEach { field ->
            CredentialField(
                field = field,
                onValueChange = { actions.onAiFieldChange(field.key, it) },
                onToggleReveal = { actions.onToggleAiReveal(field.key) },
            )
        }

        ToggleRow(
            label = "启用 Thinking 模式",
            description = "按当前 AI 提供商分别记忆；默认关闭。开启会增加响应时间和用量。",
            checked = card.thinkingEnabled,
            enabled = true,
            onCheckedChange = { actions.onToggleChange(ToggleKeys.AI_THINKING_ENABLED, it) },
        )

        var maxTagsText by remember(card.maxClassificationTags) {
            mutableStateOf(card.maxClassificationTags.toString())
        }
        FlatTextField(
            value = maxTagsText,
            onValueChange = { value ->
                maxTagsText = value.filter(Char::isDigit).take(MAX_TAG_INPUT_DIGITS)
            },
            label = { Text("每次最多细分的标签数（100–1000）") },
            supportingText = {
                Text(
                    if (
                        (maxTagsText.toIntOrNull() ?: SettingsState.DEFAULT_AI_TAG_CLASSIFICATION_MAX) >
                        SettingsState.DEFAULT_AI_TAG_CLASSIFICATION_MAX
                    ) {
                        "超过 300 会增加请求成本、处理时间和画像计算时间"
                    } else {
                        "默认 300；每批最多处理 40 个标签"
                    },
                )
            },
            singleLine = true,
            trailingIcon = {
                TextButton(
                    onClick = {
                        val value = maxTagsText.toIntOrNull()
                            ?: SettingsState.DEFAULT_AI_TAG_CLASSIFICATION_MAX
                        actions.onSetAiTagClassificationMax(
                            value.coerceIn(
                                SettingsState.MIN_AI_TAG_CLASSIFICATION_MAX,
                                SettingsState.MAX_AI_TAG_CLASSIFICATION_MAX,
                            ),
                        )
                    },
                ) { Text("应用") }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        // 预算/用量提醒。
        Text(
            text = card.budgetReminder,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ConnectionTestResultRow(test = card.test, onOpenDoc = actions.onOpenDoc)

        FlatDivider()

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onAiSave, enabled = card.hasDraft) {
                Text("保存")
            }
            FilledTonalButton(
                onClick = actions.onAiTestConnection,
                enabled = card.test.status != ConnectionTestStatus.TESTING,
            ) {
                Text("测试")
            }
        }
        if (card.configured) {
            TextButton(onClick = actions.onAiClear) { Text("清除") }
        }

        DocLinkButton(url = card.docUrl, onOpenDoc = actions.onOpenDoc)
    }
}

// endregion

internal const val MAX_TAG_INPUT_DIGITS = 4
