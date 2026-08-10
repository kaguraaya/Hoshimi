package com.acgcompass.data.repository

import com.acgcompass.data.local.dao.TagDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.WorkTagEntity
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.Work
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 作品社区标签写入器（tags + work_tags 连接表）。
 *
 * 抽出为共享组件，使两条作品入库路径——「发现/搜索落库」（[WorkRepositoryImpl] 的 persistMatches）与
 * 「个人收藏同步」（[com.acgcompass.data.sync.BangumiSyncManager]）——用**完全一致**的标签主键规则
 * [tagRowId]。否则两处各自生成不同 id，会违反 `tags` 表 `(category, name)` 唯一索引而在 upsert 时崩溃。
 *
 * `WorkEntity` 不直接保存标签；每次作品落库时必须同步维护 `tags` 与 `work_tags`，确保候选池、
 * 题材筛选和推荐器读取到一致的标签数据。
 *
 * 策略：仅对「有标签」的作品写入；对无标签作品**不**清空其既有标签（不同源版本可能各自带标签）。
 */
@Singleton
class WorkTagWriter @Inject constructor(
    private val tagDao: TagDao,
    private val workDao: WorkDao,
) {

    /**
     * 把 [works] 中每部有社区标签的作品写入 tags + work_tags：先 upsert 标签行（幂等），
     * 再以「先删后插」替换该作品的旧标签链接，保证重复入库不残留陈旧标签。
     */
    suspend fun persist(works: List<Work>) {
        val tagged = works.filter { it.tags.isNotEmpty() }
        if (tagged.isEmpty()) return
        val tagEntities = tagged.asSequence()
            .flatMap { it.tags.asSequence() }
            .distinctBy { it.category to it.name }
            .map { it.toEntity(id = tagRowId(it)) }
            .toList()
        tagDao.upsertAll(tagEntities)
        tagged.forEach { work ->
            val links = work.tags
                .distinctBy { it.category to it.name }
                .map { WorkTagEntity(workId = work.id, tagId = tagRowId(it)) }
            workDao.deleteTagLinksForWork(work.id)
            workDao.upsertWorkTags(links)
        }
    }

    /** 社区标签稳定主键（与 `tags` 表 `(category, name)` 唯一索引一致，保证两条入库路径幂等且互不冲突）。 */
    private fun tagRowId(tag: Tag): String = "${tag.category.name}:${tag.name}"
}
