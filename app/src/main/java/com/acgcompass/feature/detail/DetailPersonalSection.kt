package com.acgcompass.feature.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.FlatAssistChip
import com.acgcompass.core.designsystem.FlatFilterChip
import com.acgcompass.core.designsystem.FlatSwitch
import com.acgcompass.core.designsystem.FlatTextField
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces

// endregion

// region 个人区

/** 「在回收站中」：这部作品的待补池条目或收藏在回收站里，可以一键恢复成原来的样子。 */
@Composable
internal fun TrashNotice(text: String, onRestore: () -> Unit) {
    Surface(shape = PrismCorner.Block, color = Surfaces.raisedColor, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRestore) { Text("恢复") }
        }
    }
}

/** 回收站记录 → 详情页提示文字；不在回收站时为 null。 */
internal fun trashNoticeOf(entries: List<com.acgcompass.domain.model.TrashEntry>): String? {
    if (entries.isEmpty()) return null
    val latest = entries.maxBy { it.removedAt }
    val date = java.time.Instant.ofEpochMilli(latest.removedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val what = entries.joinToString("和") {
        if (it.kind == com.acgcompass.domain.model.TrashKind.BACKLOG) "待补池记录" else "「${it.status ?: "收藏"}」记录"
    }
    return "在回收站中：$date 移除的$what，恢复后加入时间和状态保持原样"
}

@Composable
internal fun PersonalSection(
    personal: PersonalUiModel,
    actions: PersonalSectionActions,
    options: PersonalSectionOptions,
    modifier: Modifier = Modifier,
) {
    var showEdit by remember { mutableStateOf(false) }
    SectionCard(title = "我的记录", modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.trashNotice?.let { TrashNotice(it, actions.onRestoreFromTrash) }
            // 作品归属徽章——我的库状态 / 是否在待补池 / 是否在吃灰区，互不混淆。
            MembershipBadges(personal = personal)
            if (personal.recordEditable) {
                PersonalRow(label = "我的状态", value = personal.statusText)
                PersonalRow(label = "我的评分", value = personal.ratingText)
                // 仅动画展示「我的进度」（书籍/漫画/游戏隐藏）。
                if (personal.progressEditable) {
                    PersonalRow(label = "我的进度", value = personal.progressText)
                }
                PersonalRow(label = "我的短评", value = personal.reviewText)
                // 明确展示记录的私密或公开状态。
                PersonalRow(label = "可见性", value = if (personal.isPrivate) "仅自己可见（私密）" else "公开")
                // 编辑我的记录（状态/评分/进度/短评）并回写 Bangumi。
                FilledTonalButton(onClick = { showEdit = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(text = "编辑我的记录")
                }
                if (options.localOnly) {
                    Text(
                        text = if (options.canSyncToBangumi) {
                            "这是一条 VNDB 本地记录；只有点击下方按钮时才会同步到已匹配的 Bangumi 词条。"
                        } else {
                            "这是一条 VNDB 本地记录，当前没有可可靠匹配的 Bangumi 词条。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (options.canSyncToBangumi && personal.inLibrary) {
                        FilledTonalButton(onClick = actions.onSyncToBangumi, modifier = Modifier.fillMaxWidth()) {
                            Text("同步这条记录到 Bangumi")
                        }
                    }
                }
            } else {
                // 无 Bangumi 词条——无法同步个人状态，仅提供加入待补池。
                Text(
                    text = "该作品在 Bangumi 无对应词条，无法记录 / 同步「我的状态·评分」；可先加入待补池。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (options.recordMessage != null) {
                Text(
                    text = options.recordMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // 根据是否已在待补池切换「加入 / 移出待补池」按钮形态。
            if (personal.inBacklog) {
                FilledTonalButton(
                    onClick = actions.onToggleBacklog,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "移出待补池")
                }
            } else {
                Button(
                    onClick = actions.onToggleBacklog,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = "加入待补池")
                }
            }
        }
    }
    if (showEdit) {
        EditMyRecordDialog(
            personal = personal,
            localOnly = options.localOnly,
            onDismiss = { showEdit = false },
            onSave = { status, rating, progress, comment, tags, private ->
                actions.onUpdateMyRecord(status, rating, progress, comment, tags, private)
                showEdit = false
            },
        )
    }
}

/**
 * 编辑「我的记录」对话框——状态 / 评分 / 进度 / 短评，保存即回写 Bangumi。
 * 数据源统一 Bangumi（不涉及其他源）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EditMyRecordDialog(
    personal: PersonalUiModel,
    localOnly: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (String?, Int?, Int?, String?, List<String>?, Boolean) -> Unit,
) {
    val statuses = listOf("想看", "在看", "看过", "搁置", "抛弃")
    val initRating: String = personal.ratingText?.takeWhile { it.isDigit() } ?: ""
    val initProgress: String = personal.progressText?.filter { it.isDigit() } ?: ""
    var status: String? by remember { mutableStateOf(personal.statusText) }
    var ratingText: String by remember { mutableStateOf(initRating) }
    var progressText: String by remember { mutableStateOf(initProgress) }
    var comment: String by remember { mutableStateOf(personal.reviewText ?: "") }
    var tagsText: String by remember { mutableStateOf(personal.tags.joinToString(" ")) }
    // 回显当前可见性，避免保存时意外改变私密状态。
    var isPrivate: Boolean by remember { mutableStateOf(personal.isPrivate) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑我的记录") },
        text = {
            // 编辑记录内容较多（+ 软键盘弹出）时加竖向滚动，避免底部「仅自己可见」说明 / 开关被挤出看不全。
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "提示：不选状态 = 清空（无状态）；评分 / 进度留空 = 清空。有进度会自动判为在看，看满总集数判为看过。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("状态", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    statuses.forEach { s ->
                        FlatFilterChip(
                            selected = status == s,
                            onClick = { status = if (status == s) null else s },
                            label = { Text(s) },
                        )
                    }
                }
                FlatTextField(
                    value = ratingText,
                    onValueChange = { v: String -> ratingText = v.filter { it.isDigit() }.take(2) },
                    label = { Text("评分 0-10") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (personal.progressEditable) {
                    FlatTextField(
                        value = progressText,
                        onValueChange = { v: String ->
                            val digits = v.filter { it.isDigit() }.take(4)
                            // 进度不得超过总集数上限。
                            val capped = digits.toIntOrNull()?.let { n ->
                                personal.totalEpisodes?.let { total -> n.coerceAtMost(total).toString() } ?: digits
                            } ?: digits
                            progressText = capped
                        },
                        label = {
                            Text(
                                personal.totalEpisodes?.let { "进度（话数，共 $it 话）" } ?: "进度（话数）",
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                FlatTextField(
                    value = comment,
                    onValueChange = { v: String -> comment = v },
                    label = { Text("短评") },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlatTextField(
                    value = tagsText,
                    onValueChange = { v: String -> tagsText = v },
                    label = { Text("标签（空格分隔）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // 开启后，本条评分、短评和标签不会在 Bangumi 公开展示。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("仅自己可见（私密）", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "开启后，本条评分 / 短评 / 标签在 Bangumi 个人主页不公开展示",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlatSwitch(checked = isPrivate, onCheckedChange = { isPrivate = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val rating: Int? = ratingText.toIntOrNull()?.coerceIn(0, 10)
                val progress: Int? = progressText.toIntOrNull()?.coerceAtLeast(0)
                val commentArg: String? = if (comment.isBlank()) null else comment
                val tagsArg: List<String>? = tagsText.split(Regex("[\\s,，、]+"))
                    .map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
                onSave(status, rating, progress, commentArg, tagsArg, isPrivate)
            }) { Text(if (localOnly) "仅保存到本地" else "保存并同步") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 作品归属徽章。一行内用 chip 表达三处归属，互不混淆：
 * - 我的库：有任意个人收藏记录则显示状态（如「我的库 · 在看」），否则「未入库」。
 * - 待补池：在 / 不在。
 * - 吃灰区：在吃灰区时才高亮提示。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MembershipBadges(personal: PersonalUiModel, modifier: Modifier = Modifier) {
    val libraryText = when {
        personal.statusText != null -> "我的库 · ${personal.statusText}"
        personal.inLibrary -> "已入我的库"
        else -> "未入我的库"
    }
    val backlogText = if (personal.inBacklog) "待补池 · 已加入" else "待补池 · 未加入"
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlatAssistChip(onClick = {}, label = { Text(text = libraryText) })
        FlatAssistChip(onClick = {}, label = { Text(text = backlogText) })
        if (personal.inDustMuseum) {
            FlatAssistChip(onClick = {}, label = { Text(text = "吃灰区") })
        }
    }
}

@Composable
internal fun PersonalRow(label: String, value: String?, modifier: Modifier = Modifier) {
    val isMissing = value == null
    val display = value ?: NO_DATA
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            text = display,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isMissing) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}

internal data class PersonalSectionActions(
    val onToggleBacklog: () -> Unit,
    val onUpdateMyRecord: (String?, Int?, Int?, String?, List<String>?, Boolean) -> Unit,
    val onSyncToBangumi: () -> Unit,
    val onRestoreFromTrash: () -> Unit,
)

internal data class PersonalSectionOptions(
    val recordMessage: String?,
    val trashNotice: String?,
    val localOnly: Boolean,
    val canSyncToBangumi: Boolean,
)
