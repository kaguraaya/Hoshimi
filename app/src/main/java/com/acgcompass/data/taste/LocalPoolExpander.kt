package com.acgcompass.data.taste

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.ApplicationScope
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.toBasicWorkFeature
import com.acgcompass.data.tasks.TaskRunRecorder
import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.taste.PoolExpansion
import com.acgcompass.domain.taste.WorkFeatureRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「补充作品库」：按 Bangumi 排行一段段取条目，把搜索结果里自带的标签、评分等直接存成作品资料
 * （只写 `work_features`，不走作品表的整套写库，所以快）。在应用级协程里跑，离开页面也继续；
 * 记下补到第几名，下次接着往后补。
 */
@Singleton
class LocalPoolExpander @Inject constructor(
    private val bangumi: BangumiRemoteDataSource,
    private val features: WorkFeatureRepository,
    private val workFeatureDao: WorkFeatureDao,
    private val settings: SettingsDataStore,
    private val taskRecorder: com.acgcompass.data.tasks.TaskRunRecorder,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PoolExpansion?>(null)
    val state: StateFlow<PoolExpansion?> = _state.asStateFlow()

    fun start(target: Int) {
        if (_state.value?.running == true) return
        _state.value = PoolExpansion(startRank = 0, done = 0, total = target, running = true)
        scope.launch {
            taskRecorder.started(BackgroundTaskKind.POOL_EXPAND)
            runCatching { run(target) }.onFailure { error ->
                markFailed()
                taskRecorder.failed(BackgroundTaskKind.POOL_EXPAND, TaskRunRecorder.describe(error))
            }
        }
    }

    private suspend fun run(target: Int) {
        val start = settings.localPoolExpandOffset.first()
        _state.update { it?.copy(startRank = start + 1) }
        val before = workFeatureDao.count()
        var offset = start
        var failed = false
        var more = true
        while (more && offset - start < target) {
            // 后台优先级：补充作品库一次要几十个请求，只用限速窗口的一半，不挤占首页、详情等前台请求。
            val page = bangumi.rankedSubjectDtos(offset = offset, limit = PAGE, priority = RequestPriority.BACKGROUND)
            val subjects = (page as? AppResult.Success)?.data?.first.orEmpty()
            val total = (page as? AppResult.Success)?.data?.second ?: 0
            failed = page !is AppResult.Success
            if (subjects.isNotEmpty()) {
                features.seedLightweight(subjects.mapNotNull { it.toBasicWorkFeature() })
                offset += subjects.size
                // 排行到底了，下次从头再补（顺带刷新评分）。
                settings.setLocalPoolExpandOffset(if (offset >= total) 0 else offset)
                _state.update { it?.copy(done = (offset - start).coerceAtMost(target)) }
            }
            more = subjects.isNotEmpty() && offset < total
        }
        val added = (workFeatureDao.count() - before).coerceAtLeast(0)
        _state.update { it?.copy(running = false, added = added, failed = failed && added == 0) }
        if (failed && added == 0) {
            taskRecorder.failed(BackgroundTaskKind.POOL_EXPAND, "排行榜没取到，可能是网络或限速")
        } else {
            taskRecorder.succeeded(BackgroundTaskKind.POOL_EXPAND, "新增 $added 部作品资料")
        }
    }

    private fun markFailed() = _state.update { it?.copy(running = false, failed = true, added = 0) }

    private companion object {
        /** 每段 100 部（接口每次最多回 20 部，内部会连续取 5 次）。 */
        const val PAGE = 100
    }
}
