package com.acgcompass.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.EventListener
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.acgcompass.core.network.HttpClientFactory
import com.acgcompass.core.network.HttpTimeouts
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.remote.bangumi.BangumiTokenRefresher
import com.acgcompass.data.sync.BangumiWishSync
import com.acgcompass.data.sync.CacheFillScheduler
import com.acgcompass.data.sync.RatingBackfillScheduler
import com.acgcompass.data.sync.SyncScheduler
import com.acgcompass.data.sync.TasteProfileAutoUpdater
import com.acgcompass.data.sync.TasteProfileValidationScheduler
import com.acgcompass.data.sync.WorkFeatureEnrichmentScheduler
import com.acgcompass.domain.cache.CacheAssetKind
import com.acgcompass.domain.cache.ManagedMediaRecord
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.repository.CacheRepository
import com.acgcompass.domain.taste.TagClassificationController
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Application entry point.
 *
 * Annotated with [HiltAndroidApp] to trigger Hilt's code generation and serve as the
 * application-level dependency container (local-first, no backend).
 *
 * Implements [Configuration.Provider] so WorkManager uses Hilt's [HiltWorkerFactory] to
 * construct `@HiltWorker` workers. On startup we observe the user's auto-sync
 * interval and (re)schedule the periodic Bangumi sync accordingly.
 *
 * 实现 [ImageLoaderFactory] 统一配置 Coil 封面缓存——内存上限约 25% 可用内存、
 * 磁盘层设置安全硬上限 [MAX_MANAGED_IMAGE_CACHE_BYTES]；用户选择的空间上限由缓存仓库按真实文件字节
 * 持续执行。统一 ImageLoader 让封面与截图均可选择性清理，避免出现数据库已删但文件仍占空间。
 */
@HiltAndroidApp
class AcgCompassApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var settingsDataStore: SettingsDataStore

    // 重依赖使用 dagger.Lazy，在后台初始化任务首次访问时再构建依赖图。
    @Inject lateinit var syncScheduler: Lazy<SyncScheduler>

    @Inject lateinit var cacheFillScheduler: Lazy<CacheFillScheduler>

    @Inject lateinit var ratingBackfillScheduler: Lazy<RatingBackfillScheduler>

    @Inject lateinit var bangumiWishSync: Lazy<BangumiWishSync>

    @Inject lateinit var featureEnrichmentScheduler: Lazy<WorkFeatureEnrichmentScheduler>

    @Inject lateinit var cacheRepository: Lazy<CacheRepository>

    @Inject lateinit var tasteProfileAutoUpdater: Lazy<TasteProfileAutoUpdater>

    @Inject lateinit var bangumiTokenRefresher: Lazy<BangumiTokenRefresher>

    @Inject lateinit var tagClassificationController: Lazy<TagClassificationController>

    @Inject lateinit var tasteProfileValidationScheduler: Lazy<TasteProfileValidationScheduler>

    @Inject lateinit var outboxScheduler: Lazy<com.acgcompass.data.sync.BangumiOutboxScheduler>

    @Inject lateinit var trashRepository: Lazy<com.acgcompass.domain.repository.TrashRepository>

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // 图片不走接口的限流/鉴权拦截器，但共用 IPv4 优先解析与更宽的读超时（VNDB 图片站是双栈单点）。
        .okHttpClient { HttpClientFactory.build(timeouts = HttpTimeouts.IMAGE) }
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .crossfade(true)
        .eventListenerFactory {
            object : EventListener {
                override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                    val cacheKey = result.diskCacheKey ?: return
                    val url = request.data.toString().takeIf {
                        it.startsWith("https://") || it.startsWith("http://")
                    } ?: return
                    val source = request.parameters.value<String>(ManagedMediaRecord.PARAM_SOURCE)
                        ?.let { raw -> SourceId.entries.firstOrNull { it.name == raw } }
                    val kind = request.parameters.value<String>(ManagedMediaRecord.PARAM_KIND)
                        ?.let { raw -> CacheAssetKind.entries.firstOrNull { it.name == raw } }
                        ?: CacheAssetKind.COVER
                    appScope.launch {
                        runCatching {
                            cacheRepository.get().recordMedia(
                                ManagedMediaRecord(
                                    cacheKey = cacheKey,
                                    url = url,
                                    workId = request.parameters.value(ManagedMediaRecord.PARAM_WORK_ID),
                                    source = source,
                                    kind = kind,
                                    adult = request.parameters.value(ManagedMediaRecord.PARAM_ADULT) ?: false,
                                ),
                            )
                        }
                    }
                }
            }
        }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(MAX_MANAGED_IMAGE_CACHE_BYTES)
                .build()
        }
        .build()

    override fun onCreate() {
        super.onCreate()
        // 将启动初始化移出主线程，依赖图构建和磁盘任务均在后台执行。
        // （Room / 网络客户端 / 仓库）与观察者注册都在后台完成，避免冷启动 / 开屏期间在主线程
        // 构建依赖图并与开屏动效争抢，减少掉帧。均为 best-effort，绝不阻塞 UI 起步。
        appScope.launch {
            // 自动同步间隔变化即时重新调度；0 表示关闭。
            settingsDataStore.autoSyncIntervalMinutes
                .distinctUntilChanged()
                .onEach { minutes -> syncScheduler.get().apply(minutes) }
                .launchIn(appScope)
            settingsDataStore.cacheBackgroundFillEnabled
                .distinctUntilChanged()
                .onEach { enabled -> cacheFillScheduler.get().apply(enabled) }
                .launchIn(appScope)
            settingsDataStore.scoreDiffAutoBackfill
                .distinctUntilChanged()
                .onEach { enabled -> ratingBackfillScheduler.get().apply(enabled) }
                .launchIn(appScope)
            // 个人收藏变化（同步入库 / 详情页改状态 / 加待补池）→ 防抖后自动重算口味画像。
            tasteProfileAutoUpdater.get().start(appScope)
            // 本地作品资料（标签、评分、封面）在后台分批补齐，联网时才跑。
            runCatching { featureEnrichmentScheduler.get().enqueue() }
            tasteProfileValidationScheduler.get().ensureScheduled()
            // Construct the durable manual-classification coordinator so an interrupted run can resume.
            tagClassificationController.get()
            // 待补池里还没同步到 Bangumi 的作品继续推送「想看」。
            runCatching { bangumiWishSync.get().resumeOnStartup() }
            // Bangumi OAuth token 启动期自动续期（best-effort，静默；无 refresh_token 时跳过）。
            runCatching { bangumiTokenRefresher.get().refreshIfNeeded() }
            // 本地改了还没推到 Bangumi 的收藏继续推送；清理超过保留期的回收站记录。
            runCatching { outboxScheduler.get().enqueue() }
            runCatching { trashRepository.get().purgeExpired() }
        }
    }

    companion object {
        /** Hard safety ceiling; the user-selected space policy is enforced by [CacheRepository]. */
        const val MAX_MANAGED_IMAGE_CACHE_BYTES: Long = 5_120L * 1024 * 1024
    }
}
