package com.acgcompass.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.acgcompass.core.ui.LocalFloatingBarInset
import com.acgcompass.core.ui.withoutTop
import com.acgcompass.feature.backlog.BacklogRoute
import com.acgcompass.feature.cache.CacheItemCleanupScreen
import com.acgcompass.feature.cache.CacheManagementScreen
import com.acgcompass.feature.detail.DETAIL_ARG_WORK_ID
import com.acgcompass.feature.detail.DetailRoute
import com.acgcompass.feature.detail.detailRoute
import com.acgcompass.feature.discover.DiscoverRoute
import com.acgcompass.feature.home.HomeRoute
import com.acgcompass.feature.imports.ImportRoute
import com.acgcompass.feature.library.LIBRARY_ARG_TAB
import com.acgcompass.feature.library.MyLibraryRoute
import com.acgcompass.feature.library.libraryRoute
import com.acgcompass.feature.mine.AboutScreen
import com.acgcompass.feature.mine.MineRoute
import com.acgcompass.feature.onboarding.OnboardingRoute
import com.acgcompass.feature.onboarding.OnboardingViewModel
import com.acgcompass.feature.recommender.RECOMMENDER_ARG_TAGS
import com.acgcompass.feature.recommender.RecommenderRoute
import com.acgcompass.feature.recommender.recommenderRoute
import com.acgcompass.feature.settings.SettingsScreen
import com.acgcompass.feature.settings.SettingsSourceScreen
import com.acgcompass.feature.taste.TasteProfileRoute
import com.acgcompass.feature.timemachine.TimeMachineRoute

/**
 * 应用导航根。
 *
 * 由单 Activity 调用：装配 [Scaffold]（底部五栏导航）+ [AppNavHost]，并根据
 * [OnboardingViewModel.shouldShowOnboarding] 在首次启动时把用户引导到引导页。
 *
 * - 底栏仅在顶层目的地（五栏）显示；进入详情 / 设置 / 导入 / 推荐器 / 引导等嵌套路由时隐藏。
 * - 五栏之间切换维持自然返回栈：切换时 `popUpTo` 起始页并 `saveState`，避免栈无限增长。
 */
@Composable
@Suppress("LongParameterList")
fun AcgApp(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    onboardingViewModel: OnboardingViewModel = hiltViewModel(),
    predictiveBackEnabled: Boolean = true,
    bangumiAuthNotice: String? = null,
    onBangumiAuthNoticeShown: () -> Unit = {},
) {
    val shouldShowOnboarding by onboardingViewModel.shouldShowOnboarding.collectAsStateWithLifecycle()

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    val currentTopLevel = TopLevelDestination.fromRoute(currentRoute)

    // 首启引导门控：当 DataStore 表明尚未展示引导时，导航到引导页。
    // 初始值为 false，老用户不会闪现引导页（见 OnboardingViewModel 注释）。
    LaunchedEffect(shouldShowOnboarding) {
        if (shouldShowOnboarding && currentRoute != AppDestination.Onboarding.route) {
            navController.navigate(AppDestination.Onboarding.route) {
                launchSingleTop = true
            }
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(bangumiAuthNotice) {
        bangumiAuthNotice?.let {
            snackbarHostState.showSnackbar(it)
            onBangumiAuthNoticeShown()
        }
    }

    val showDock = TopLevelDestination.isTopLevelRoute(currentRoute)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        // 统一 inset 单一来源模型。
        // 外层 Scaffold 用**默认** contentWindowInsets（消费状态栏 + 导航栏 + 底栏高度），
        // 把消费后的 inset 通过 innerPadding 下发，并用 consumeWindowInsets 标记已消费，
        // 使各页 TopAppBar / 内容统一用零 inset，不各自消费状态栏（各页各自消费状态栏时
        // 时机不一致，会导致首张卡片/封面贴顶或被裁切）。
        // 底栏不放进 bottomBar 槽：那样 Scaffold 会给它留出一整条矩形空带，圆角外面看不到下面的内容。
        // 而是叠在内容之上，按圆角轮廓遮挡；可滚动内容通过 LocalFloatingBarInset 加大底部内边距让出位置。
        snackbarHost = {
            SnackbarHost(snackbarHostState, Modifier.padding(bottom = if (showDock) DOCK_SPACE else 0.dp))
        },
    ) { innerPadding ->
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalFloatingBarInset provides if (showDock) DOCK_SPACE else 0.dp) {
                AppNavHost(
                    navController = navController,
                    predictiveBackEnabled = predictiveBackEnabled,
                    // 只替页面让出底部（导航条）；状态栏交给各页顶栏自己包住，模糊顶栏才能延伸到屏幕顶端。
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding.withoutTop())
                        .consumeWindowInsets(innerPadding.withoutTop()),
                )
            }
            if (showDock) {
                AcgBottomNavigation(
                    currentDestination = currentTopLevel,
                    onSelect = { tab -> navController.navigateToTopLevel(tab) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** 底栏高度 64 + 上下各 8 的留白。 */
private val DOCK_SPACE = 80.dp

/**
 * 应用 [NavHost]：声明五栏占位屏与嵌套路由（详情 / 设置 / 导入 / 推荐器 / 引导）。
 *
 * 起始目的地固定为首页；首启引导通过 [AcgApp] 的门控按需进入，完成后回到首页。
 */
@Composable
fun AppNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    predictiveBackEnabled: Boolean = true,
) {
    val activeEntry by navController.currentBackStackEntryAsState()
    NavHost(
        navController = navController,
        startDestination = AppDestination.Home.route,
        modifier = modifier,
        // 顶层栏目之间无方向语义，直接切换；只要一端是嵌套页，就使用对称的 push/pop 转场：
        // Material「共享轴 X」风格——短距离位移 + 淡入淡出（整屏平移在大屏上显得拖沓，且两页会并排露出）。
        // 四个 transition 必须使用相同的双端判断，确保进入和返回动画成对且页面不会重叠。
        enterTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) {
                EnterTransition.None
            } else {
                slideInHorizontally(navSpec()) { it / NAV_SLIDE_FRACTION } + fadeIn(navSpec())
            }
        },
        exitTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) {
                ExitTransition.None
            } else {
                slideOutHorizontally(navSpec()) { -it / NAV_SLIDE_FRACTION } + fadeOut(navSpec())
            }
        },
        popEnterTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) {
                EnterTransition.None
            } else {
                slideInHorizontally(navSpec()) { -it / NAV_SLIDE_FRACTION } + fadeIn(navSpec())
            }
        },
        popExitTransition = {
            if (initialState.isTopLevel() && targetState.isTopLevel()) {
                ExitTransition.None
            } else {
                slideOutHorizontally(navSpec()) { it / NAV_SLIDE_FRACTION } + fadeOut(navSpec())
            }
        },
    ) {
        // region 底部五栏
        composable(AppDestination.Home.route) {
            HomeRoute(
                onOpenRecommender = { presetTags ->
                    navController.navigateOnce(recommenderRoute(presetTags))
                },
                // 搜索专页尚未接入，暂时复用发现页。
                onOpenSearch = {
                    navController.navigateOnce(AppDestination.Discover.route)
                },
                onOpenImport = {
                    navController.navigateOnce(AppDestination.Import.route)
                },
                onOpenWork = { workId ->
                    navController.navigateOnce(detailRoute(workId))
                },
                onOpenSettings = {
                    navController.navigateOnce(AppDestination.Settings.route)
                },
                onOpenHomeModules = {
                    navController.navigateOnce(AppDestination.HomeModules.route)
                },
                onOpenSchedule = {
                    navController.navigateOnce(AppDestination.Schedule.route)
                },
            )
        }
        composable(AppDestination.Discover.route) {
            DiscoverRoute(
                onOpenWork = { workId ->
                    navController.navigateOnce(detailRoute(workId))
                },
                onOpenExploreQueue = {
                    navController.navigateOnce(AppDestination.ExploreQueue.route)
                },
                onOpenVisualNovels = {
                    navController.navigateOnce(AppDestination.VisualNovels.route)
                },
            )
        }
        composable(AppDestination.VisualNovels.route) {
            com.acgcompass.feature.visualnovel.VisualNovelRoute(
                onBack = { navController.popBackOnce() },
                onOpenWork = { workId -> navController.navigateOnce(detailRoute(workId)) },
            )
        }
        composable(AppDestination.ExploreQueue.route) {
            com.acgcompass.feature.explore.ExploreQueueRoute(
                onOpenWork = { workId -> navController.navigateOnce(detailRoute(workId)) },
                onBack = { navController.popBackOnce() },
            )
        }
        composable(AppDestination.Backlog.route) {
            BacklogRoute(
                onOpenDetail = { workId ->
                    navController.navigateOnce(detailRoute(workId))
                },
                onGoImport = {
                    navController.navigateOnce(AppDestination.Import.route)
                },
                onGoSearch = {
                    navController.navigateOnce(AppDestination.Discover.route)
                },
                onOpenDustMuseum = {
                    navController.navigateOnce(AppDestination.DustMuseum.route)
                },
            )
        }
        composable(AppDestination.TimeMachine.route) {
            TimeMachineRoute(
                onOpenWork = { workId ->
                    navController.navigateOnce(detailRoute(workId))
                },
            )
        }
        composable(AppDestination.Mine.route) {
            MineRoute(
                onNavigateToSettings = {
                    navController.navigateOnce(AppDestination.Settings.route)
                },
                onOpenPrivacySettings = {
                    navController.navigateOnce(settingsSourceRoute(com.acgcompass.feature.settings.PRIVACY_SOURCE_KEY))
                },
                onOpenTasteProfile = {
                    navController.navigateOnce(AppDestination.TasteProfile.route)
                },
                onOpenAbout = {
                    navController.navigateOnce(AppDestination.About.route)
                },
                onOpenLibrary = { tab ->
                    navController.navigateOnce(libraryRoute(tab))
                },
            )
        }
        // endregion

        // region 嵌套路由
        nestedRoutes(navController)
        // endregion
    }
    // 在 NavHost 之后注册，使关闭预测性返回时由普通回调接管嵌套页面。
    BackHandler(
        enabled = !predictiveBackEnabled && !TopLevelDestination.isTopLevelRoute(activeEntry?.destination?.route),
    ) {
        navController.popBackOnce()
    }
}

/**
 * 注册非底栏的嵌套路由；尚未实现的目的地使用明确的占位屏。
 */
private fun NavGraphBuilder.nestedRoutes(navController: NavHostController) {
    composable(
        route = "vndb_producer/{producerId}?producerName={producerName}",
        arguments = listOf(
            navArgument("producerId") { type = NavType.StringType },
            navArgument("producerName") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) {
        com.acgcompass.feature.visualnovel.VndbProducerRoute(
            onBack = { navController.popBackOnce() },
            onOpenWork = { workId -> navController.navigateOnce(detailRoute(workId)) },
        )
    }
    composable(
        route = AppDestination.Detail.route,
        arguments = listOf(
            navArgument(DETAIL_ARG_WORK_ID) { type = NavType.StringType },
        ),
    ) {
        // workId 由路由参数经 SavedStateHandle 注入 DetailViewModel（见 DetailViewModel）。
        DetailRoute(
            onOpenWork = { workId -> navController.navigateOnce(detailRoute(workId)) },
            onOpenVndbProducer = { producerId, producerName ->
                val encodedName = android.net.Uri.encode(producerName)
                navController.navigateOnce("vndb_producer/$producerId?producerName=$encodedName")
            },
        )
    }
    composable(AppDestination.Import.route) {
        ImportRoute(onBack = { navController.popBackOnce() })
    }
    composable(
        route = AppDestination.Recommender.route,
        arguments = listOf(
            navArgument(RECOMMENDER_ARG_TAGS) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) {
        // presetTags 由路由参数经 SavedStateHandle 注入 RecommenderViewModel（首页今日状态预填）。
        RecommenderRoute(
            onBack = { navController.popBackOnce() },
            onOpenWork = { workId ->
                navController.navigateOnce(detailRoute(workId))
            },
        )
    }
    composable(
        route = "${AppDestination.Settings.route}?section={section}",
        arguments = listOf(
            navArgument("section") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        SettingsScreen(
            onNavigateBack = { navController.popBackOnce() },
            expandPrivacy = entry.arguments?.getString("section") == "privacy",
            onClearCache = { navController.navigateOnce(AppDestination.CacheManagement.route) },
            onOpenCacheManagement = { navController.navigateOnce(AppDestination.CacheManagement.route) },
            onOpenSource = { sourceKey ->
                navController.navigateOnce(settingsSourceRoute(sourceKey))
            },
        )
    }
    composable(
        route = AppDestination.SettingsSource.route,
        arguments = listOf(
            navArgument("source") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) { entry ->
        SettingsSourceScreen(
            sourceKey = entry.arguments?.getString("source").orEmpty(),
            onNavigateBack = { navController.popBackOnce() },
            onBangumiLogin = { navController.navigateOnce(AppDestination.BangumiOAuth.route) },
            onClearCache = { navController.navigateOnce(AppDestination.CacheManagement.route) },
        )
    }
    composable(AppDestination.CacheManagement.route) {
        CacheManagementScreen(
            onBack = { navController.popBackOnce() },
            onOpenItemCleanup = { navController.navigateOnce(AppDestination.CacheItems.route) },
            onResetComplete = {
                navController.navigate(AppDestination.Onboarding.route) {
                    popUpTo(0) { inclusive = true }
                }
            },
        )
    }
    composable(AppDestination.CacheItems.route) {
        CacheItemCleanupScreen(onBack = { navController.popBackOnce() })
    }
    composable(AppDestination.BangumiOAuth.route) {
        com.acgcompass.feature.auth.BangumiOAuthRoute(
            onBack = { navController.popBackStack() },
        )
    }
    composable(AppDestination.Onboarding.route) {
        OnboardingRoute(
            onFinished = {
                // 引导完成：返回首页并清除引导页，避免回退又回到引导。
                navController.navigate(AppDestination.Home.route) {
                    popUpTo(AppDestination.Onboarding.route) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    composable(AppDestination.About.route) {
        AboutScreen(
            onBack = { navController.popBackOnce() },
            onOpenDataSources = { navController.navigateOnce(AppDestination.AboutDataSources.route) },
            onOpenLicenses = { navController.navigateOnce(AppDestination.AboutLicenses.route) },
            onOpenDisclaimer = { navController.navigateOnce(AppDestination.AboutDisclaimer.route) },
        )
    }
    composable(AppDestination.AboutDataSources.route) {
        com.acgcompass.feature.mine.AboutDocumentScreen(
            title = "数据来源",
            paragraphs = com.acgcompass.feature.mine.ABOUT_DATA_SOURCE_PARAGRAPHS,
            onBack = { navController.popBackOnce() },
        )
    }
    composable(AppDestination.AboutLicenses.route) {
        com.acgcompass.feature.mine.AboutLicenseScreen(
            onBack = { navController.popBackOnce() },
        )
    }
    composable(AppDestination.AboutDisclaimer.route) {
        com.acgcompass.feature.mine.AboutDocumentScreen(
            title = "免责声明",
            paragraphs = com.acgcompass.feature.mine.ABOUT_DISCLAIMER_PARAGRAPHS,
            onBack = { navController.popBackOnce() },
        )
    }
    composable(AppDestination.HomeModules.route) {
        com.acgcompass.feature.home.HomeModulesRoute(onBack = { navController.popBackOnce() })
    }
    composable(AppDestination.Schedule.route) {
        com.acgcompass.feature.home.BroadcastScheduleRoute(
            onBack = { navController.popBackOnce() },
            onOpenWork = { workId -> navController.navigateOnce(detailRoute(workId)) },
        )
    }
    composable(AppDestination.DustMuseum.route) {
        com.acgcompass.feature.backlog.DustMuseumRoute(
            onOpenWork = { workId -> navController.navigateOnce(detailRoute(workId)) },
            onBack = { navController.popBackOnce() },
        )
    }
    composable(AppDestination.TasteProfile.route) {
        TasteProfileRoute(
            onBack = { navController.popBackOnce() },
            onOpenSettings = { navController.navigateOnce(AppDestination.Settings.route) },
            onOpenManualReview = { navController.navigateOnce(AppDestination.TagManualReview.route) },
        )
    }
    composable(AppDestination.TagManualReview.route) {
        com.acgcompass.feature.taste.TagManualReviewRoute(
            onBack = { navController.popBackOnce() },
        )
    }
    composable(
        route = AppDestination.Library.route,
        arguments = listOf(
            navArgument(LIBRARY_ARG_TAB) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
    ) {
        // 初始 Tab 由路由参数经 SavedStateHandle 注入 MyLibraryViewModel。
        MyLibraryRoute(
            onBack = { navController.popBackOnce() },
            onOpenWork = { workId -> navController.navigateOnce(detailRoute(workId)) },
        )
    }
}

/**
 * 切换底部五栏的标准导航行为：
 * - `popUpTo` 图的起始目的地并 `saveState`，避免在栈中堆叠多个顶层页面。
 * - `launchSingleTop` 防止重复入栈同一页面。
 * - `restoreState` 恢复已保存的滚动与选择状态。
 */
/**
 * 当前页面是否处于可交互（RESUMED）状态。转场动画期间旧页面已不是 RESUMED，
 * 据此可以丢弃「动画还没播完时的第二次点击」。
 */
private fun NavHostController.isCurrentResumed(): Boolean =
    currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED

/**
 * 进入嵌套页（防连点）：快速双击卡片 / 按钮时只导航一次，避免同一页面被压栈两次、
 * 返回时要按两下。
 */
private fun NavHostController.navigateOnce(route: String) {
    if (isCurrentResumed()) navigate(route)
}

/**
 * 返回上一页（防连点）：转场期间再次点击返回会连退两级，退到起始页之前还会出现空白页。
 * 仅用于用户点击触发的返回；由流程自动触发的返回（OAuth 回调、引导完成）仍直接 pop。
 */
private fun NavHostController.popBackOnce() {
    if (isCurrentResumed()) popBackStack()
}

private fun NavHostController.navigateToTopLevel(tab: TopLevelDestination) {
    navigate(tab.route) {
        // 始终回到图的起始页（首页）之上重建目标栏，确保每个 Tab 进入正确页面。
        // 刻意不使用 saveState/restoreState：避免「点首页却回到发现页」的状态错配；
        // 各页内部状态（如搜索结果）由各自 ViewModel / 仓库缓存负责保留，不依赖导航返回栈。
        popUpTo(graph.startDestinationId) {
            inclusive = false
        }
        launchSingleTop = true
    }
}

/** 下钻页（详情等）滑入 / 滑出时长（ms）：快速利落承载整屏位移，不拖沓（纯滑动、无 fade）。 */
private const val NAV_SLIDE_MS = 280

/** 位移距离 = 屏宽 / 此值（共享轴只需要一小段位移提示方向）。 */
private const val NAV_SLIDE_FRACTION = 6

private fun <T> navSpec() = tween<T>(NAV_SLIDE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)

/** 该目的地是否为底部五栏之一（顶层）。用于区分「同级切换（瞬切）」与「下钻（滑入）」动画。 */
private fun NavBackStackEntry.isTopLevel(): Boolean = TopLevelDestination.isTopLevelRoute(destination.route)
