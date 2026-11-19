package com.acgcompass.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.acgcompass.data.sync.AccountSwitchChoice

/**
 * 发现换了 Bangumi 账号时的选择：合并到新账号 / 旧账号的收藏移到回收站 / 稍后再说。
 * 点对话框外面等同于「稍后再说」：同步保持暂停，下次同步会再问。
 */
@Composable
fun AccountSwitchDialog(from: String, to: String, onChoose: (AccountSwitchChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = { onChoose(AccountSwitchChoice.CANCEL) },
        title = { Text("切换了 Bangumi 账号") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("上次同步的是 @$from，现在登录的是 @$to。本地的收藏来自旧账号，要怎么处理？")
                Text(
                    "· 合并到新账号：本地收藏和新账号的收藏合并，以较新的一方为准，新账号没有的会推送到 Bangumi\n" +
                        "· 移到回收站：旧账号的收藏移进本地回收站（30 天内可恢复），然后同步新账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onChoose(AccountSwitchChoice.MERGE) }, modifier = Modifier.fillMaxWidth()) {
                        Text("合并到新账号")
                    }
                    FilledTonalButton(
                        onClick = { onChoose(AccountSwitchChoice.MOVE_TO_TRASH) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("旧账号的收藏移到回收站") }
                    TextButton(onClick = { onChoose(AccountSwitchChoice.CANCEL) }, modifier = Modifier.fillMaxWidth()) {
                        Text("稍后再说")
                    }
                }
            }
        },
        confirmButton = {},
    )
}
