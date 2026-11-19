package com.acgcompass.data.local.migration

import com.acgcompass.data.local.dao.WorkIdMigrationDao
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.WorkIds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 启动时把还在用原始数字编号的其它平台作品改成「来源:编号」（[WorkIds]）。没有旧数据时只是一次查询。
 *
 * 有 Bangumi 收藏指向的旧行不改：那一行是被同号的其它平台作品覆盖过的 Bangumi 作品，下次同步会用 Bangumi 资料写回。
 */
@Singleton
class WorkIdMigration @Inject constructor(
    private val dao: WorkIdMigrationDao,
) {
    /** 返回改了几部。 */
    suspend fun run(): Int {
        var migrated = 0
        dao.legacyForeignWorks().forEach { legacy ->
            val source = SourceId.entries.firstOrNull { it.name == legacy.primarySource } ?: return@forEach
            if (!WorkIds.isNamespaced(source) || dao.bangumiCollectionCount(legacy.id) > 0) return@forEach
            dao.migrate(legacy.id, WorkIds.local(source, legacy.id), source.name)
            migrated++
        }
        return migrated
    }
}
