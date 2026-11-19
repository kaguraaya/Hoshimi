package com.acgcompass.app.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.HourglassFull
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 应用内所有可导航目的地的封闭模型。
 *
 * 分为两类：
 * - [TopLevelDestination]：底部五栏（首页 / 发现 / 待补池 / 时光机 / 我的），固定顺序、可高亮当前页。
 * - [AppDestination]：包含五栏在内的全部路由，外加嵌套路由（详情 / 设置 / 导入 / 推荐器 / 引导）。
 *
 * route 字符串集中在此声明，避免散落字面量，便于导航与测试引用。
 */
sealed interface AppDestination {
    /** Compose Navigation 用的稳定路由标识。 */
    val route: String

    // region 底部五栏（顶层目的地）

    data object Home : AppDestination {
        override val route: String = "home"
    }

    data object Discover : AppDestination {
        override val route: String = "discover"
    }

    data object Backlog : AppDestination {
        override val route: String = "backlog"
    }

    data object TimeMachine : AppDestination {
        override val route: String = "time_machine"
    }

    data object Mine : AppDestination {
        override val route: String = "mine"
    }

    // endregion

    // region 嵌套路由（非底栏）

    /**
     * 作品详情页。route 携带 `workId` 参数（模板 `detail/{workId}`），
     * 由 [com.acgcompass.feature.detail.DETAIL_ROUTE_PATTERN] 定义、
     * [com.acgcompass.feature.detail.detailRoute] 构造具体跳转路径。
     */
    data object Detail : AppDestination {
        override val route: String = com.acgcompass.feature.detail.DETAIL_ROUTE_PATTERN
    }

    data object Settings : AppDestination {
        override val route: String = "settings"
    }

    data object CacheManagement : AppDestination {
        override val route: String = "cache_management"
    }

    data object CacheItems : AppDestination {
        override val route: String = "cache_items"
    }

    data object VisualNovels : AppDestination {
        override val route: String = "visual_novels"
    }

    data object Import : AppDestination {
        override val route: String = "import"
    }

    /**
     * 「今晚看什么」推荐器。route 携带可选 `presetTags` 参数（首页今日状态预填的「想看标签」），
     * 由 [com.acgcompass.feature.recommender.RECOMMENDER_ROUTE_PATTERN] 定义、
     * [com.acgcompass.feature.recommender.recommenderRoute] 构造具体跳转路径。
     */
    data object Recommender : AppDestination {
        override val route: String = com.acgcompass.feature.recommender.RECOMMENDER_ROUTE_PATTERN
    }

    data object Onboarding : AppDestination {
        override val route: String = "onboarding"
    }

    /** 我的口味码：生成并分享。 */
    data object TasteCode : AppDestination {
        override val route: String = "taste_code"
    }

    /** 打开别人的口味码（粘贴 / 相册二维码）。 */
    data object TasteCodeOpen : AppDestination {
        override val route: String = "taste_code/open"
    }

    /** 口味相性：和对方的口味码对比。也能从 `hoshimi://taste/<口味码>` 链接直接打开。 */
    data object TasteCompatibility : AppDestination {
        override val route: String = "taste_code/compat?code={code}"
    }

    /** 番剧回顾：本季 / 上季 / 今年 / 去年看完的作品和分享图。 */
    data object WatchReport : AppDestination {
        override val route: String = "watch_report"
    }

    /** 口味排行：本地作品库按口味匹配度排序。 */
    data object TasteRanking : AppDestination {
        override val route: String = "taste_ranking"
    }

    /** 回收站：移出待补池 / 清空的记录与 Bangumi 上已删除的收藏，可恢复。 */
    data object Trash : AppDestination {
        override val route: String = "trash"
    }

    /** 数据安全：自动备份与升级前数据库备份（设置 → 备份与导出 → 数据安全）。 */
    data object DataSafety : AppDestination {
        override val route: String = "data_safety"
    }

    /** 关于页。 */
    data object About : AppDestination {
        override val route: String = "about"
    }

    data object AboutDataSources : AppDestination {
        override val route: String = "about/data_sources"
    }

    data object AboutLicenses : AppDestination {
        override val route: String = "about/licenses"
    }

    data object AboutDisclaimer : AppDestination {
        override val route: String = "about/disclaimer"
    }

    /** 口味画像页：评分习惯 / 称号 / 口味黑洞。 */
    data object TasteProfile : AppDestination {
        override val route: String = "taste_profile"
    }

    data object TagManualReview : AppDestination {
        override val route: String = "tag_manual_review"
    }

    /**
     * 我的库 / 我的收藏页：同步入库的用户收藏列表，按状态分组；route 携带可选 `tab` 参数
     * （模板见 [com.acgcompass.feature.library.LIBRARY_ROUTE_PATTERN]）。
     */
    data object Library : AppDestination {
        override val route: String = com.acgcompass.feature.library.LIBRARY_ROUTE_PATTERN
    }

    /** 首页模块偏好页（设置 → 首页模块）。 */
    data object HomeModules : AppDestination {
        override val route: String = "home_modules"
    }

    data object Schedule : AppDestination {
        override val route: String = "broadcast_schedule"
    }

    /** 吃灰馆独立页面（待补池 → 吃灰馆）。 */
    data object DustMuseum : AppDestination {
        override val route: String = "dust_museum"
    }

    /**
     * 设置二级页（设置 → 某数据源 / AI Provider / 隐私）。route 携带 `source` 参数
     * （[SourceId] 枚举名或隐私键），由 [settingsSourceRoute] 构造具体跳转路径。
     */
    data object SettingsSource : AppDestination {
        override val route: String = "settings_source?source={source}"
    }

    /** Bangumi OAuth2 授权登录页（内嵌 WebView）。从设置 → Bangumi 的「用 Bangumi 登录」进入。 */
    data object BangumiOAuth : AppDestination {
        override val route: String = "bangumi_oauth"
    }

    /** 探索队列；从发现页进入，用于展示可能感兴趣的新作品。 */
    data object ExploreQueue : AppDestination {
        override val route: String = com.acgcompass.feature.explore.EXPLORE_QUEUE_ROUTE
    }

    // endregion
}

/** 构造设置二级页跳转路径。`sourceKey` 为 [com.acgcompass.data.credential.SourceId] 枚举名或隐私键。 */
fun settingsSourceRoute(sourceKey: String): String = "settings_source?source=$sourceKey"

/**
 * 底部导航栏的一个栏目。封装路由、标题、选中/未选中图标，
 * 供 [com.acgcompass.app.navigation.AcgBottomNavigation] 渲染与高亮当前页。
 */
enum class TopLevelDestination(
    val destination: AppDestination,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    HOME(
        destination = AppDestination.Home,
        label = "首页",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
    ),
    DISCOVER(
        destination = AppDestination.Discover,
        label = "发现",
        selectedIcon = Icons.Filled.Explore,
        unselectedIcon = Icons.Outlined.Explore,
    ),
    BACKLOG(
        destination = AppDestination.Backlog,
        label = "待补池",
        // 书签组：「先存着，之后补」；选中实心、未选空心。
        selectedIcon = Icons.Filled.Bookmarks,
        unselectedIcon = Icons.Outlined.Bookmarks,
    ),
    TIME_MACHINE(
        destination = AppDestination.TimeMachine,
        label = "时光机",
        // 沙漏：时光流转；选中是装满的沙漏、未选是空沙漏。
        selectedIcon = Icons.Filled.HourglassFull,
        unselectedIcon = Icons.Filled.HourglassEmpty,
    ),
    MINE(
        destination = AppDestination.Mine,
        label = "我的",
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person,
    ),
    ;

    val route: String get() = destination.route

    companion object {
        /** 固定的五栏顺序。 */
        val ENTRIES: List<TopLevelDestination> = entries.toList()

        /** 判断给定 route 是否属于某个底部栏目（用于高亮当前页）。 */
        fun fromRoute(route: String?): TopLevelDestination? = entries.firstOrNull { it.route == route }

        /** 给定 route 是否为顶层目的地（决定是否显示底栏）。 */
        fun isTopLevelRoute(route: String?): Boolean = fromRoute(route) != null
    }
}
