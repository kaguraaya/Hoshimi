package com.acgcompass.feature.tastecode

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.acgcompass.core.designsystem.FlatTextField
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.PrismCorner
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.TagPillRow
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.GallerySaver
import com.acgcompass.core.ui.LocalAppMessenger
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.domain.tastecode.RepresentativeReason
import com.acgcompass.domain.tastecode.TasteCode
import com.acgcompass.domain.tastecode.TasteCodeCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TasteCodeRoute(onBack: () -> Unit, viewModel: TasteCodeViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TasteCodeScreen(
        state = state,
        actions = TasteCodeActions(
            onBack = onBack,
            onToggleWork = viewModel::onToggleWork,
            onRemoveAll = viewModel::onRemoveAllWorks,
            onRestoreDefault = viewModel::onRestoreDefaultWorks,
            onNicknameChange = viewModel::onNicknameChange,
            onGenerate = viewModel::onGenerate,
            onEdit = viewModel::onEdit,
        ),
    )
}

/**
 * 我的口味码：上面是口味码里会带上的内容预览，中间可选昵称、逐部确认代表作，最后生成二维码和文本码。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TasteCodeScreen(state: TasteCodeUiState, actions: TasteCodeActions, modifier: Modifier = Modifier) {
    AcgScreenScaffold(
        title = "我的口味码",
        modifier = modifier,
        navigationIcon = {
            IconButton(
                onClick = actions.onBack,
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        },
    ) { innerPadding ->
        when {
            state.loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            state.unavailable || state.base == null -> UnavailableHint()
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = ScreenContentPadding.lazyList(innerPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "preview") { PreviewCard(state.base, state.nickname, state.includedCount) }
                val generated = state.generated
                if (generated != null) {
                    item(
                        key = "result",
                    ) { ResultCard(generated, state.nickname.trim().ifEmpty { null }, actions.onEdit) }
                } else {
                    item(key = "nickname") { NicknameCard(state.nickname, actions.onNicknameChange) }
                    item(key = "works") { WorksCard(state, actions) }
                    item(key = "generate") {
                        Button(onClick = actions.onGenerate, modifier = Modifier.fillMaxWidth()) { Text("生成口味码") }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnavailableHint() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("还生成不了口味码", style = MaterialTheme.typography.titleMedium)
        Text(
            "口味码来自你的口味画像。先同步 Bangumi 收藏、给看过的作品打分，口味画像算出来后就能生成。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 口味码里会带上的内容：打分风格、最喜欢 / 回避的标签、代表作数量。 */
@Composable
private fun PreviewCard(base: TasteCode, nickname: String, includedWorks: Int) {
    PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${nickname.trim().ifEmpty { "我" }}的口味", style = MaterialTheme.typography.titleLarge)
            Text(
                styleLine(base),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (base.liked.isNotEmpty()) {
                Text("最喜欢", style = MaterialTheme.typography.labelLarge)
                TagPillRow(base.liked.take(PREVIEW_TAGS).map { it.tag })
            }
            if (base.avoided.isNotEmpty()) {
                Text("比较回避", style = MaterialTheme.typography.labelLarge)
                TagPillRow(base.avoided.take(PREVIEW_AVOIDED).map { it.tag })
            }
            Text(
                "包含：打分风格 · 口味标签 · 年代分布 · 代表作 $includedWorks 部。不含短评和账号信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NicknameCard(nickname: String, onChange: (String) -> Unit) {
    PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("昵称（可选）", style = MaterialTheme.typography.titleMedium)
            FlatTextField(
                value = nickname,
                onValueChange = onChange,
                singleLine = true,
                placeholder = { Text("不填显示为 Ta") },
                supportingText = { Text("${nickname.length} / ${TasteCode.MAX_NICKNAME}") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 代表作：按入选原因分组的封面网格，点一下去掉 / 恢复。 */
@Composable
private fun WorksCard(state: TasteCodeUiState, actions: TasteCodeActions) {
    PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "代表作 ${state.includedCount} / ${state.items.size}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (state.includedCount > 0) {
                    TextButton(onClick = actions.onRemoveAll) { Text("全部去掉") }
                } else {
                    TextButton(onClick = actions.onRestoreDefault) { Text("恢复默认") }
                }
            }
            Text(
                "对方能看到这些作品和你的评分，用来找共同作品和互相推荐。点封面去掉不想分享的。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RepresentativeReason.entries.forEach { reason ->
                val group = state.items.filter { it.work.reason == reason }
                if (group.isNotEmpty()) WorkGroup(reasonLabel(reason), group, actions.onToggleWork)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkGroup(label: String, items: List<RepresentativeItem>, onToggle: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 留半个 dp 的余量：三列刚好铺满时，像素取整会把第三列挤到下一行。
            val tileWidth = (maxWidth - GRID_GAP * (GRID_COLUMNS - 1)) / GRID_COLUMNS - 0.5.dp
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(GRID_GAP),
            ) {
                items.forEach { item ->
                    WorkTile(item, Modifier.width(tileWidth)) { onToggle(item.work.subjectId) }
                }
            }
        }
    }
}

@Composable
private fun WorkTile(item: RepresentativeItem, modifier: Modifier, onToggle: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_RATIO)
                .clip(PrismCorner.Cover)
                .background(Surfaces.raisedColor)
                .alpha(if (item.included) 1f else EXCLUDED_ALPHA),
        ) {
            if (item.coverUrl != null) {
                AsyncImage(
                    model = item.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(onClick = onToggle, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = BADGE_ALPHA)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = if (item.included) "去掉" else "恢复",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
        Text(
            if (item.included) "${item.title ?: "条目 ${item.work.subjectId}"} · ${item.work.rating}" else "已去掉",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.run { if (item.included) onSurface else onSurfaceVariant },
        )
    }
}

/** 生成结果：二维码 + 文本码，可以复制、分享，或把二维码存成图片。 */
@Composable
private fun ResultCard(code: String, nickname: String?, onEdit: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val showMessage = LocalAppMessenger.current
    val scope = rememberCoroutineScope()
    val qr = remember(code) { QrCodes.encode(code, QR_PIXELS).asImageBitmap() }
    PrismCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                bitmap = qr,
                contentDescription = "口味码二维码",
                modifier = Modifier.fillMaxWidth(
                    QR_WIDTH_FRACTION,
                ).aspectRatio(1f).clip(PrismCorner.Cover).background(Color.White),
            )
            Text(
                code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "对方在 Hoshimi「我的 → 打开口味码」里粘贴这段码，或者选这张二维码图片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    showMessage("已复制口味码")
                }) { Text("复制") }
                FilledTonalButton(onClick = {
                    scope.launch {
                        val image = withContext(Dispatchers.Default) { TasteCodeImage.render(code, nickname) }
                        val saved = GallerySaver.savePng(context, image, "Hoshimi_taste_code")
                        showMessage(if (saved) "二维码图片已保存到相册" else "保存失败，可以先截图保存")
                    }
                }) { Text("存图") }
                Button(onClick = {
                    val text = "我的 Hoshimi 口味码，来比比我们的口味：\n${TasteCodeCodec.LINK_PREFIX}$code"
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "分享口味码"))
                }) { Text("分享") }
            }
            TextButton(onClick = onEdit) { Text("重新选择") }
        }
    }
}

private const val PREVIEW_TAGS = 8
private const val PREVIEW_AVOIDED = 4
private const val GRID_COLUMNS = 3
private val GRID_GAP = 10.dp
private const val POSTER_RATIO = 0.72f
private const val EXCLUDED_ALPHA = 0.3f
private const val BADGE_ALPHA = 0.55f
private const val QR_PIXELS = 720
private const val QR_WIDTH_FRACTION = 0.72f
