package com.acgcompass.data.local.migration

import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.local.dao.WorkIdMigrationDao
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.WorkIds
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

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
        repairFeatureKeys()
        return migrated
    }

    /**
     * 作品资料表的键应当是 Bangumi 条目号。早先改编号时这张表没跟着改，留下两种坏行：
     * - 键是「来源:编号」：直接删，用到时会按 Bangumi 条目号重新取；
     * - 键是旧的数字编号、内容却是那部其它平台作品（标题对得上）：和同号的 Bangumi 条目串了（例如排行里显示
     *   CLANNAD，点进去是另一部作品），删掉。标题对不上的说明就是那个 Bangumi 条目自己的资料，保留。
     */
    private suspend fun repairFeatureKeys() {
        dao.deleteNamespacedFeatures()
        dao.namespacedWorks().forEach { work ->
            val oldId = work.id.substringAfter(':')
            val featureTitles = dao.featureTitlesJson(oldId)
                ?.let { runCatchingCancellable { TITLES_JSON.decodeFromString<List<String>>(it) }.getOrNull() }
                ?: return@forEach
            val workTitles = (listOfNotNull(work.canonicalTitle, work.titleJa, work.titleCn) + work.aliases)
                .map(::normalize).filter { it.isNotEmpty() }.toSet()
            if (featureTitles.any { normalize(it) in workTitles }) dao.deleteFeature(oldId)
        }
    }

    private fun normalize(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

    private companion object {
        val TITLES_JSON = Json { ignoreUnknownKeys = true }
    }
}
