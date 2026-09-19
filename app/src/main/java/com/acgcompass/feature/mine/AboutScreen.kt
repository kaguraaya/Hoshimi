package com.acgcompass.feature.mine

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.acgcompass.BuildConfig
import com.acgcompass.R
import com.acgcompass.core.common.AppInfo
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.core.designsystem.IconWell
import com.acgcompass.core.designsystem.NavRow
import com.acgcompass.core.designsystem.RowGroup
import com.acgcompass.core.designsystem.RowGroupDivider
import com.acgcompass.core.designsystem.SectionHeader
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.ui.AcgTopBarHeight
import com.acgcompass.core.ui.copyOnLongPress

/**
 * 关于页。展示应用与版本信息，并提供数据来源、开源许可和免责声明的独立入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenDataSources: () -> Unit = {},
    onOpenLicenses: () -> Unit = {},
    onOpenDisclaimer: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                expandedHeight = AcgTopBarHeight,
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = TopAppBarDefaults.windowInsets,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(16.dp)
                // 标题栏下方额外 8dp 呼吸（共约 24dp），底部额外留白避免末项贴边。
                .padding(top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 品牌头——应用图标 + Hoshimi 名 + 一句话简介。
            AboutBrandHeader()
            // 版本 / 作者 / 仓库信息卡。版本号读 BuildConfig（随构建自动同步）；
            // 作者 / 仓库地址取自集中的 AppInfo（单一常量），GitHub 按钮用系统浏览器打开仓库。
            AboutInfoSection(
                version = "${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）",
                author = AppInfo.AUTHOR,
                repoUrl = AppInfo.REPO_URL,
            )
            AboutFeaturesSection()
            SectionHeader("更多信息")
            RowGroup {
                NavRow(
                    title = "数据来源",
                    supporting = "作品资料、评分和放送信息从哪里来",
                    onClick = onOpenDataSources,
                    leading = { IconWell(Icons.Outlined.Storage) },
                )
                RowGroupDivider()
                NavRow(
                    title = "开源许可",
                    supporting = "运行依赖、开发工具和许可说明",
                    onClick = onOpenLicenses,
                    leading = { IconWell(Icons.Outlined.Description) },
                )
                RowGroupDivider()
                NavRow(
                    title = "免责声明",
                    supporting = "推荐结果、第三方数据和内容版权的边界",
                    onClick = onOpenDisclaimer,
                    leading = { IconWell(Icons.Outlined.Gavel) },
                )
            }
        }
    }
}

/**
 * 品牌头——圆角应用图标（复用 launcher 前景）+ 应用名 Hoshimi + 一句话简介，居中排布。
 */
@Composable
private fun AboutBrandHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = "${AppInfo.APP_NAME} 应用图标",
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Surfaces.raisedColor),
        )
        Text(
            text = AppInfo.APP_NAME,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "本地优先的 ACG 作品发现与管理工具，支持多平台资料聚合、口味分析和推荐。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** 版本 / 作者 / 仓库：成组的行；仓库一行点按用浏览器打开，长按复制地址。 */
@Composable
private fun AboutInfoSection(version: String, author: String, repoUrl: String) {
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("版本与开发")
        RowGroup {
            NavRow(title = "版本", onClick = null, trailing = { AboutValue(version) })
            RowGroupDivider()
            NavRow(title = "作者", onClick = null, trailing = { AboutValue(author) })
            RowGroupDivider()
            NavRow(
                title = "项目仓库",
                supporting = repoUrl,
                onClick = { runCatching { uriHandler.openUri(repoUrl) } },
                modifier = Modifier.copyOnLongPress(repoUrl),
                leading = {
                    IconWell(icon = ImageVector.vectorResource(id = R.drawable.ic_github))
                },
            )
        }
    }
}

@Composable
private fun AboutValue(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 一句话介绍主要功能，方便第一次打开「关于」的人知道这个应用能做什么。 */
@Composable
private fun AboutFeaturesSection() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader("能做什么")
        RowGroup {
            ABOUT_FEATURES.forEachIndexed { index, (icon, title, text) ->
                if (index > 0) RowGroupDivider()
                NavRow(title = title, supporting = text, onClick = null, leading = { IconWell(icon) }, trailing = null)
            }
        }
    }
}

private val ABOUT_FEATURES: List<Triple<ImageVector, String, String>> = listOf(
    Triple(Icons.Outlined.NightsStay, "今晚看什么", "按可用时间、心情和待补池，挑出稳妥 / 探索 / 随机三部"),
    Triple(Icons.Outlined.Explore, "探索队列", "左右滑认识新作品，你的选择会慢慢影响推荐"),
    Triple(Icons.Outlined.Insights, "口味画像与预计评分", "从你的评分里学习偏好，估计你会给一部作品打几分"),
    Triple(Icons.Outlined.Search, "多平台搜索与评分对比", "同时查 Bangumi、AniList、VNDB 等，合并结果、对比评分"),
    Triple(Icons.Outlined.Inventory2, "待补池与时光机", "记下想看的作品，回看自己的观看与评分变化"),
    Triple(Icons.Outlined.Lock, "本地优先", "数据存在手机上；AI 功能只在你配置了自己的服务后才会使用"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutDocumentScreen(title: String, paragraphs: List<String>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                expandedHeight = AcgTopBarHeight,
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            paragraphs.forEach { paragraph ->
                Text(paragraph, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutLicenseScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val notice = androidx.compose.runtime.remember(context) {
        runCatching {
            context.assets.open("third_party_notices.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse { "许可证清单暂时无法读取，请重新安装或联系开发者。" }
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                expandedHeight = AcgTopBarHeight,
                title = { Text("开源许可") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "以下清单由当前发布包的实际依赖生成，包含直接依赖、传递依赖以及开发测试工具。",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(notice, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

val ABOUT_DATA_SOURCE_PARAGRAPHS = listOf(
    "Bangumi\n主作品资料与个人数据来源。用于中文标题、封面、简介、社区评分、社区标签、角色、Staff、" +
        "关联作品、个人收藏和放送日历。应用优先读取本地缓存；详情、搜索、手动同步或缓存过期时才请求接口。" +
        "Bangumi 未参与本应用的开发或背书。",
    "AniList\n补充跨源匹配、评分与高置信匹配后的精确放送时间。已有 Bangumi 主资料时不会用 AniList 简介覆盖；请求结果会进入本地缓存，匹配不可靠时保持来源分离。",
    "Tenrai\n补充动画评分、跨源关联和 Bangumi、AniList 均无法给出时刻时的放送时间。" +
        "Tenrai 不作为已有 Bangumi 条目的简介主源，访问受单并发、限速、冷却和缓存控制。",
    "MyAnimeList\n仅在用户自行配置并启用官方访问能力时提供补充评分和条目关联。未配置时不会请求，也不会把其他来源评分冒充为 MyAnimeList 评分。",
    "VNDB\n用于视觉小说标题、别名、制作信息、简介、评分、封面和截图。Bangumi 存在可靠匹配时优先显示 Bangumi 中文资料；VNDB 图片与文本按各自缓存策略保存。",
    "Jikan 诊断适配器\n只保留独立连接诊断和兼容性检查，不参与默认详情、探索、榜单、画像或缓存填充链路。诊断失败不会被解释成其他来源不可用。",
    "用户配置的 AI 服务\n仅在用户主动启动 AI 功能后，把当前任务必需的文本和标签发送到用户选择的服务。" +
        "Hoshimi 不提供公共 AI 中继，也不会把 Bangumi、AniList、VNDB 或 OAuth 凭据发送给 AI 服务。",
)

val ABOUT_DISCLAIMER_PARAGRAPHS = listOf(
    "推荐与分析\nHoshimi 的口味匹配、推荐、雷达和 AI 分析依据有限的个人记录与公开资料计算，只用于帮助整理和选择作品。结果会受样本数量、标签质量和来源差异影响，不代表客观评价，也不保证适合每个人。",
    "资料与版权\n作品名称、封面、截图、简介、角色资料、标签和评分归原作者、制作发行方及相应数据平台所有。网络资料可能延迟、缺失或发生变化；应用会优先保留本地缓存，并在无法确认时明确显示缺失状态。",
    "外部内容\nHoshimi 不提供、托管或索引未经授权的播放、下载和破解资源。应用打开的第三方链接由对应网站负责，用户应根据所在地规则选择正规观看、购买或游玩渠道。",
    "账号授权\n第三方登录由对应平台的授权页面完成，应用不会提供账号密码输入表单。" +
        "令牌仅用于用户授权的同步操作并保存在本机凭据存储中。私有构建配置的 OAuth Transport " +
        "只处理授权与令牌刷新，不代理公共作品接口。用户可以随时退出登录或清除本地数据。",
    "AI 服务\n用户主动使用 AI 翻译、标签分类、雷达或匹配分析时，完成当前任务所需的公开作品资料、标签、" +
        "评论摘要或用户选中的文本会发送到所配置的 AI 提供商。API Key 只用于对应服务鉴权，" +
        "不写入日志或普通备份；具体数据处理规则仍以该提供商条款为准。",
    "本地数据与备份\n收藏、评分、待补池、画像、缓存和设置主要保存在设备本地。卸载、清除本地数据、设备故障或导入错误可能造成损失；重要数据应定期导出备份，并在导入后核对结果。缓存可重新下载，但个人记录不能依赖缓存恢复。",
    "责任边界\n本应用按现状提供。开发者会修复已知问题，但无法保证所有第三方接口持续可用，也不对因来源停服、限流、地区限制、错误资料、用户配置或未备份数据造成的间接损失作保证。",
)

@Preview(showBackground = true)
@Composable
private fun AboutScreenPreview() {
    AcgCompassTheme {
        AboutScreen(onBack = {})
    }
}
