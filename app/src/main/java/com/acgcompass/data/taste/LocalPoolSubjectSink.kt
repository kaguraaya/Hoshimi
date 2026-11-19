package com.acgcompass.data.taste

import com.acgcompass.core.common.ApplicationScope
import com.acgcompass.data.remote.bangumi.BangumiSubjectDto
import com.acgcompass.data.remote.bangumi.BangumiSubjectSink
import com.acgcompass.data.remote.bangumi.toBasicWorkFeature
import com.acgcompass.domain.taste.WorkFeatureRepository
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 把搜索、榜单里刷到的条目存成本地作品库的基础资料（标签和标注人数、评分、集数、平台、封面），
 * 这些作品随即可以参与口味排行。已有更完整资料的作品不会被降级（[WorkFeatureRepository.seedLightweight] 会合并）。
 */
@Singleton
class LocalPoolSubjectSink @Inject constructor(
    private val features: Lazy<WorkFeatureRepository>,
    @ApplicationScope private val scope: CoroutineScope,
) : BangumiSubjectSink {
    override fun accept(subjects: List<BangumiSubjectDto>) {
        val seeds = subjects.asSequence()
            .filter { it.id > 0 && it.tags.isNotEmpty() }
            .mapNotNull { it.toBasicWorkFeature() }
            .toList()
        if (seeds.isEmpty()) return
        scope.launch { runCatching { features.get().seedLightweight(seeds) } }
    }
}
