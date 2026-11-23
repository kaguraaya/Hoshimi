@file:Suppress("TooManyFunctions") // 一个页面拆成多个小型私有 Composable。

package com.acgcompass.feature.explore

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.acgcompass.core.designsystem.PrismCard
import com.acgcompass.core.designsystem.SurfaceLevel
import com.acgcompass.core.designsystem.Surfaces
import com.acgcompass.core.designsystem.TagPill
import com.acgcompass.core.designsystem.TagPillRow
import com.acgcompass.core.designsystem.TagPillTone
import com.acgcompass.core.ui.AcgTopBarHeight
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/** 探索队列路由（续 / 算法文档「探索队列」）。route 无参。 */
const val EXPLORE_QUEUE_ROUTE: String = "explore_queue"

/**
 * 探索卡封面高度。Crop 只取封面中的一段（不把整张封面铺满卡片），为标题/匹配度/理由/标签
 * 留出空间，整体更「卡片」而非「大图」，兼顾美观。正反面一致。
 */
private val FRONT_COVER_HEIGHT = 176.dp

/**
 * 探索队列路由入口：连接 [ExploreQueueViewModel]，把状态与回调下发给无状态 [ExploreQueueScreen]。
 *
 * @param onOpenWork 点击卡片进入作品详情。
 * @param onBack 返回（顶栏返回箭头 / 空态返回）。
 */
@Composable
fun ExploreQueueRoute(
    onOpenWork: (String) -> Unit,
    onBack: () -> Unit,
    onOpenPreferences: () -> Unit = {},
    viewModel: ExploreQueueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExploreQueueScreen(
        state = state,
        onLike = viewModel::onLike,
        onSkip = viewModel::onSkip,
        onRegenerate = viewModel::generate,
        onOpenWork = onOpenWork,
        onEnsureSynopsis = viewModel::loadSynopsis,
        onBack = onBack,
        onOpenPreferences = onOpenPreferences,
    )
}

/**
 * 探索队列页（无状态）。卡片堆叠 + 左右滑手势（右滑加入待补池 / 左滑暂不感兴趣）+ 底部操作按钮；
 * 口味匹配度为视觉核心（大字 + 进度条），社区评分弱化到详情页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongParameterList") // 页面骨架：状态与回调逐个传入，便于截图测试。
@Composable
fun ExploreQueueScreen(
    state: ExploreQueueUiState,
    onLike: () -> Unit,
    onSkip: () -> Unit,
    onRegenerate: () -> Unit,
    onOpenWork: (String) -> Unit,
    onEnsureSynopsis: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPreferences: () -> Unit = {},
) {
    var showSwipeHelp by remember { mutableStateOf(false) }
    if (showSwipeHelp) SwipeHelpDialog(onDismiss = { showSwipeHelp = false })
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            Column {
                ExploreTopBar(onBack = onBack, onHelp = { showSwipeHelp = true }, onOpenPreferences = onOpenPreferences)
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is ExploreQueueUiState.Loading ->
                    CircularProgressIndicator()

                is ExploreQueueUiState.Preparing ->
                    PreparingContent(state)

                is ExploreQueueUiState.Empty ->
                    EmptyOrError(message = state.message, onRetry = onRegenerate, onBack = onBack)

                is ExploreQueueUiState.Error ->
                    EmptyOrError(message = state.message, onRetry = onRegenerate, onBack = onBack)

                is ExploreQueueUiState.Finished ->
                    FinishedContent(
                        liked = state.liked,
                        skipped = state.skipped,
                        onRegenerate = onRegenerate,
                        onBack = onBack,
                    )

                is ExploreQueueUiState.Ready -> {
                    val card = state.cards.getOrNull(state.index)
                    if (card == null) {
                        FinishedContent(liked = 0, skipped = 0, onRegenerate = onRegenerate, onBack = onBack)
                    } else {
                        ReadyContent(
                            card = card,
                            position = state.index + 1,
                            total = state.cards.size,
                            onLike = onLike,
                            onSkip = onSkip,
                            onOpenWork = onOpenWork,
                            onEnsureSynopsis = onEnsureSynopsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreparingContent(state: ExploreQueueUiState.Preparing) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("正在准备口味匹配", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (state.total > 0) {
            LinearProgressIndicator(
                progress = { state.completed.toFloat() / state.total },
                modifier = Modifier
                    .fillMaxWidth(PREPARING_BAR_WIDTH_FRACTION)
                    .height(6.dp)
                    .clip(CircleShape),
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            Text(
                "${state.completed} / ${state.total}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CircularProgressIndicator()
        }
    }
}

/**
 * 当前卡片 + 进度 + 操作按钮。
 *
 * 手势：拖动时卡片随手指位移并轻微倾斜，同时浮现「加入 / 暂不」印章提示当前方向的含义；
 * 越过阈值松手即飞出并触发对应操作（带一次触感反馈），否则回弹。阈值按 dp 计（按像素算的话，
 * 高密度屏上轻轻一碰就会误触发）。底部按钮走同一套飞出动画，保证两种操作观感一致。
 */
// 手势 + 翻面 + 入场动画的状态需集中在同一作用域。
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
private fun ReadyContent(
    card: ExploreCardUiModel,
    position: Int,
    total: Int,
    onLike: () -> Unit,
    onSkip: () -> Unit,
    onOpenWork: (String) -> Unit,
    onEnsureSynopsis: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val swipeThreshold = with(density) { SWIPE_THRESHOLD.toPx() }
    val flyOutDistance = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() } * FLY_OUT_FACTOR

    val offsetX = remember(card.workId) { Animatable(0f) }
    // 新卡片入场：从略小、半透明过渡到原位，替代上一张飞出后「凭空出现」。
    val appear = remember(card.workId) { Animatable(0f) }
    var flipped by remember(card.workId) { mutableStateOf(false) }
    val rotation = remember(card.workId) { Animatable(0f) }
    // 飞出动画进行中：忽略重复点击 / 拖动，防止同一张卡被处理两次。
    var committing by remember(card.workId) { mutableStateOf(false) }
    // 卡片的实际尺寸：倾斜的旋转中心按它计算，不依赖图层自己的尺寸。
    var cardSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(card.workId) { appear.animateTo(1f, tween(durationMillis = 220)) }
    LaunchedEffect(card.workId, flipped) {
        if (flipped) onEnsureSynopsis(card.workId)
        rotation.animateTo(if (flipped) FULL_FLIP_DEGREES else 0f, animationSpec = tween(durationMillis = 420))
    }

    fun commit(like: Boolean) {
        if (committing) return
        committing = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch {
            offsetX.animateTo(if (like) flyOutDistance else -flyOutDistance, tween(durationMillis = 220))
            if (like) onLike() else onSkip()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BatchProgress(position = position, total = total)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val dragProgress = (offsetX.value / swipeThreshold).coerceIn(-1f, 1f)
            PrismCard(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { cardSize = it }
                    // 手势放在所有变换图层之前：拖动量取屏幕坐标。放在翻面（rotationY = 180°）之后的话，
                    // 背面朝上时 x 轴是镜像的，手指往右卡片却往左。
                    .pointerInput(card.workId) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    committing -> Unit
                                    offsetX.value >= swipeThreshold -> commit(like = true)
                                    offsetX.value <= -swipeThreshold -> commit(like = false)
                                    else -> scope.launch { offsetX.animateTo(0f) }
                                }
                            },
                            onDragCancel = { if (!committing) scope.launch { offsetX.animateTo(0f) } },
                            onHorizontalDrag = { _, dragAmount ->
                                if (!committing) scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                            },
                        )
                    }
                    .graphicsLayer {
                        val tilt = (offsetX.value / swipeThreshold).coerceIn(-MAX_TILT_PROGRESS, MAX_TILT_PROGRESS) *
                            MAX_TILT_DEGREES
                        applySwipeTransform(offsetX.value, tilt, cardSize)
                    }
                    .graphicsLayer {
                        rotationY = rotation.value
                        val scale = APPEAR_START_SCALE + (1f - APPEAR_START_SCALE) * appear.value
                        scaleX = scale
                        scaleY = scale
                        alpha = appear.value
                        cameraDistance = CAMERA_DISTANCE_FACTOR * this.density
                    }
                    .clickable(onClickLabel = if (flipped) "翻回正面" else "查看简介") { flipped = !flipped },
                // 扁平：靠色块与大圆角区分层次，不用描边和投影。
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = Surfaces.raisedColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                if (rotation.value <= HALF_FLIP_DEGREES) {
                    WithSwipeStamps(dragProgress) { ExploreCardFront(card) }
                } else {
                    Box(modifier = Modifier.graphicsLayer { rotationY = FULL_FLIP_DEGREES }) {
                        WithSwipeStamps(dragProgress) { ExploreCardBack(card = card, onOpenWork = onOpenWork) }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        SwipeActions(onSkip = { commit(like = false) }, onLike = { commit(like = true) })
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * 拖动时的位移与倾斜：卡片底边中点跟着手指平移，以它为圆心转动，越往上偏得越多。
 *
 * 旋转中心固定在图层原点，再用平移把它挪到底边中点：对任意点 P，结果是 R·(P − C) + C + (dx, 0)，
 * 其中 C 是按 [size] 算出的底边中点。不依赖图层的 transformOrigin 和图层尺寸。
 */
private fun GraphicsLayerScope.applySwipeTransform(dx: Float, degrees: Float, size: IntSize) {
    val radians = Math.toRadians(degrees.toDouble())
    val cos = cos(radians).toFloat()
    val sin = sin(radians).toFloat()
    val pivotX = size.width / 2f
    val pivotY = size.height * SWIPE_PIVOT_Y
    transformOrigin = TransformOrigin(0f, 0f)
    rotationZ = degrees
    translationX = dx + pivotX - (pivotX * cos - pivotY * sin)
    translationY = pivotY - (pivotX * sin + pivotY * cos)
}

/** 批次进度：进度条 + 「第几张 / 共几张」。 */
@Composable
private fun BatchProgress(position: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LinearProgressIndicator(
            progress = { position.toFloat() / total.coerceAtLeast(1) },
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(CircleShape),
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Text(
            "$position / $total",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 卡片内容 + 拖动方向印章（右拖显示「加入待补池」，左拖显示「暂不」），正反两面都显示。 */
@Composable
private fun WithSwipeStamps(dragProgress: Float, content: @Composable () -> Unit) {
    Box {
        content()
        SwipeStamp(
            text = "加入待补池",
            visibility = dragProgress.coerceAtLeast(0f),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.TopStart),
        )
        SwipeStamp(
            text = "暂不",
            visibility = (-dragProgress).coerceAtLeast(0f),
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

/** 底部操作按钮：与左右滑等价，走同一套飞出动画。 */
@Composable
private fun SwipeActions(onSkip: () -> Unit, onLike: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = onSkip,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("暂不")
        }
        Button(
            onClick = onLike,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 52.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("加入待补池")
        }
    }
}

/** 拖动时浮现在卡片角上的方向提示；[visibility] 0→1 随拖动距离渐显。 */
@Composable
private fun SwipeStamp(
    text: String,
    visibility: Float,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    if (visibility <= 0f) return
    Surface(
        modifier = modifier
            .padding(16.dp)
            .graphicsLayer { alpha = visibility },
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/**
 * 卡片正面：封面（带底部渐变遮罩与「换个口味」标识）、标题、口味匹配度、推荐理由与题材标签。
 * 内容区域可滚动，长标题或多标签不会被截断。
 */
@Composable
private fun ExploreCardFront(card: ExploreCardUiModel) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 封面占卡片上部约四成半：海报多为竖图，给足高度；下方信息区可滚动。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(COVER_WEIGHT)
                .background(Surfaces.colorOf(SurfaceLevel.Inset)),
        ) {
            if (card.coverUrl == null) {
                Icon(
                    Icons.Outlined.Explore,
                    contentDescription = null,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp),
                    tint = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(card.coverUrl).crossfade(true).build(),
                contentDescription = card.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (card.discovery) {
                TagPill(
                    text = "换个口味",
                    tone = TagPillTone.Tertiary,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f - COVER_WEIGHT)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 16.dp),
        ) {
            Text(card.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (card.meta.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    card.meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        "口味匹配度",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        card.tasteQualitative,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    card.tastePercent,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { card.tasteFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
            card.predictionText?.let {
                Spacer(Modifier.height(10.dp))
                TagPill(text = it, tone = TagPillTone.Primary)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                card.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (card.tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                TagPillRow(card.tags)
            }
            Spacer(Modifier.height(12.dp))
        }
        Text(
            "点击卡片看简介 · 左右滑动表态",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExploreTopBar(onBack: () -> Unit, onHelp: () -> Unit, onOpenPreferences: () -> Unit) {
    TopAppBar(
        expandedHeight = AcgTopBarHeight,
        title = { Text("探索队列", fontWeight = FontWeight.Bold) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            IconButton(onClick = onOpenPreferences) {
                Icon(Icons.Outlined.Tune, contentDescription = "推荐偏好")
            }
            IconButton(onClick = onHelp) {
                Icon(Icons.Outlined.Info, contentDescription = "左右滑说明")
            }
        },
    )
}

/** 左右滑会怎样影响口味：马上生效的只有这一批卡片的顺序，画像要等下次自然重建。 */
@Composable
private fun SwipeHelpDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("知道了") } },
        title = { Text("左右滑会怎样影响口味") },
        text = { Text(SWIPE_HELP, style = MaterialTheme.typography.bodyMedium) },
    )
}

private const val SWIPE_HELP =
    "右滑：加入待补池。左滑：这次先不推，较长时间内不再推荐这部。\n\n" +
        "马上生效：这一批后面的卡片会按你的左右滑重新排——同类标签右滑多就多推，左滑多就少推。\n\n" +
        "不会马上生效：滑动不会重建口味画像，也不改口味匹配度和预计评分。等下次因为评分、收藏变化重建画像时，" +
        "待补池里的作品会算作「你想看的」，用来判断你常看哪类作品；左滑只是很弱的信号。\n\n" +
        "滑动只说明想不想看，不代表看完会打几分；你的评分永远比滑动重要。"

/** 卡片背面展示作品简介、详情入口与翻回提示。 */
@Composable
private fun ExploreCardBack(card: ExploreCardUiModel, onOpenWork: (String) -> Unit) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        AsyncImage(
            model = card.coverUrl,
            contentDescription = card.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(FRONT_COVER_HEIGHT)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
        )
        Column(modifier = Modifier.padding(16.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("简介", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            // 三态——有简介 / 加载中 / 确实无简介（不伪造）。
            val synopsis = card.synopsis
            when {
                synopsis != null -> Text(
                    synopsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                card.synopsisLoading -> Text(
                    "简介加载中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "暂无简介（点击「查看详情」在详情页加载）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onOpenWork(card.workId) }, modifier = Modifier.fillMaxWidth()) {
                Text("查看详情")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "再次点击卡片翻回正面",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 状态页顶部的色块图标（完成 / 空态共用）。 */
@Composable
private fun StateBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Surfaces.raisedColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun FinishedContent(liked: Int, skipped: Int, onRegenerate: () -> Unit, onBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateBadge(Icons.Filled.DoneAll)
        Text("本批探索完成", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TagPill(text = "加入待补池 $liked 部", tone = TagPillTone.Primary)
            TagPill(text = "暂不 $skipped 部", tone = TagPillTone.Neutral)
        }
        Text(
            "你的选择会用来调整下一批的题材比例",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRegenerate, modifier = Modifier.fillMaxWidth()) { Text("再来一批") }
        FilledTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回发现") }
    }
}

@Composable
private fun EmptyOrError(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateBadge(Icons.Outlined.Explore)
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("再试一次") }
        FilledTonalButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回发现") }
    }
}

/** 触发左右滑操作所需的最小拖动距离。 */
private val SWIPE_THRESHOLD = 96.dp

/** 飞出距离 = 屏宽 × 此系数（确保倾斜的卡片完全离开屏幕）。 */
private const val FLY_OUT_FACTOR = 1.3f

/** 拖到阈值时卡片的倾斜角；越过阈值后继续加大，最多到 [MAX_TILT_PROGRESS] 倍。 */
private const val MAX_TILT_DEGREES = 8f
private const val MAX_TILT_PROGRESS = 1.5f

/** 倾斜的旋转中心：卡片底边（高度的比例）。 */
private const val SWIPE_PIVOT_Y = 1f

/** 翻面动画：转过半圈后切换到背面内容。 */
private const val HALF_FLIP_DEGREES = 90f
private const val FULL_FLIP_DEGREES = 180f
private const val CAMERA_DISTANCE_FACTOR = 12f

/** 准备态进度条占屏宽的比例。 */
private const val PREPARING_BAR_WIDTH_FRACTION = 0.6f

/** 正面封面占卡片高度的比例。 */
private const val COVER_WEIGHT = 0.45f

/** 新卡片入场的起始缩放。 */
private const val APPEAR_START_SCALE = 0.94f
