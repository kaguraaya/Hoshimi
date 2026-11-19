package com.acgcompass.feature.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.IconWell
import com.acgcompass.core.designsystem.NavRow
import com.acgcompass.core.designsystem.RowGroup
import com.acgcompass.core.designsystem.RowGroupDivider
import com.acgcompass.core.designsystem.SectionHeader

/** 分享与回顾：番剧回顾（本季 / 年度）、口味码（生成 / 打开）。 */
@Composable
internal fun ShareCard(share: MineShareActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("分享与回顾")
        RowGroup {
            NavRow(
                title = "番剧回顾",
                supporting = "这一季、这一年看完了哪些，生成分享图",
                onClick = share.onOpenReport,
                leading = { IconWell(Icons.Outlined.CalendarMonth) },
            )
            RowGroupDivider()
            NavRow(
                title = "我的口味码",
                supporting = "把口味变成一段码或二维码，发给朋友比一比",
                onClick = share.onOpenTasteCode,
                leading = { IconWell(Icons.Outlined.QrCode2) },
            )
            RowGroupDivider()
            NavRow(
                title = "打开口味码",
                supporting = "粘贴朋友的口味码或选二维码截图，看看有多合拍",
                onClick = share.onOpenTasteCodeReader,
                leading = { IconWell(Icons.Outlined.People) },
            )
        }
    }
}
