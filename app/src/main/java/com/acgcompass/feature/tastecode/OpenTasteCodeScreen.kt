package com.acgcompass.feature.tastecode

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.core.designsystem.FlatTextField
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.ui.AcgScreenScaffold
import com.acgcompass.core.ui.ScreenContentPadding
import com.acgcompass.domain.tastecode.TasteCodeCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 打开别人的口味码：粘贴口味码 / 分享文案，或从相册选二维码截图（不需要相机权限）。
 * 认出来后进入口味相性页。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OpenTasteCodeScreen(onBack: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun tryOpen(input: String) {
        val code = TasteCodeCodec.extract(input)?.takeIf { TasteCodeCodec.decode(it) != null }
        if (code != null) {
            error = null
            onOpen(code)
        } else {
            error = "没有认出口味码，请确认复制完整（以 HSM1. 开头）"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val decoded = withContext(Dispatchers.Default) {
                runCatchingCancellable {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()?.let(QrCodes::decode)
            }
            if (decoded == null) error = "这张图片里没有找到二维码" else tryOpen(decoded)
        }
    }
    AcgScreenScaffold(
        title = "打开口味码",
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = ScreenContentPadding.top(innerPadding))
                .padding(horizontal = ScreenContentPadding.Horizontal),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PasteCard(
                text = text,
                error = error,
                onTextChange = {
                    text = it
                    error = null
                },
                onPaste = {
                    text = clipboard.getText()?.text.orEmpty()
                    tryOpen(text)
                },
                onOpen = { tryOpen(text) },
            )
            PickImageCard {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun PasteCard(
    text: String,
    error: String?,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onOpen: () -> Unit,
) {
    PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("粘贴口味码", style = MaterialTheme.typography.titleMedium)
            FlatTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("HSM1.…，或整段分享文字") },
                isError = error != null,
                supportingText = error?.let { message -> { Text(message) } },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPaste) { Text("从剪贴板粘贴") }
                Button(onClick = onOpen, enabled = text.isNotBlank()) { Text("打开") }
            }
        }
    }
}

@Composable
private fun PickImageCard(onPick: () -> Unit) {
    PrismCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("二维码图片", style = MaterialTheme.typography.titleMedium)
            Text(
                "收到的是二维码图片或截图？先保存到相册，再在这里选择它，会自动识别。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FilledTonalButton(onClick = onPick) { Text("从相册选择") }
        }
    }
}
