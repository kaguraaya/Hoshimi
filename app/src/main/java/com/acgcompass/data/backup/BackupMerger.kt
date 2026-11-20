package com.acgcompass.data.backup

/**
 * 纯函数式备份合并器（领域无关、无 Android / DAO 依赖，可在 JVM 单测中直接调用）。
 *
 * 合并不覆盖：以业务主键为准把 `incoming`
 * 合并进 `local`：
 * - 本地不存在该主键 → **新增**（added）。
 * - 主键冲突且该表带时间戳 → **默认保留较新 `updatedAt`**：仅当导入数据严格更新于本地时才采用
 *   导入值（updated / [Resolution.USED_INCOMING]），否则保留本地（[Resolution.KEPT_LOCAL]），
 *   并记录一条 [Conflict] 供 UI 提示。
 * - 主键冲突且该表无时间戳（如标签分类法、作品-标签关联）→ **保留本地**，记录冲突但不计为更新。
 * - 仅本地存在的主键 → 原样保留。
 *
 * schema 2 及以上恢复导入文件中的非敏感设置；schema 1 缺少完整设置快照，因此保留本地设置。
 * 凭据（`credentials`）不参与合并，结果恒为 `null`。
 */
object BackupMerger {

    /** 单条主键冲突的解决方式。 */
    enum class Resolution {
        /** 保留了本地数据（未被导入覆盖）。 */
        KEPT_LOCAL,

        /** 采用了更新的导入数据。 */
        USED_INCOMING,
    }

    /**
     * 单条合并冲突明细。
     *
     * @property table 业务表名。
     * @property key 业务主键。
     * @property resolution 冲突解决方式。
     */
    data class Conflict(
        val table: String,
        val key: String,
        val resolution: Resolution,
    )

    /**
     * 合并结果。
     *
     * @property merged 合并后的信封（含全部业务数据；`credentials=null`）。
     * @property addedCount 新增主键总数。
     * @property updatedCount 被导入数据更新的主键总数。
     * @property conflictCount 主键冲突总数（含已保留本地与已采用导入两种）。
     * @property keptLocalCount 冲突里保留本地的条数。
     * @property conflicts 冲突样例，最多 [MAX_CONFLICT_SAMPLES] 条：导入自己的备份时几乎每一行都冲突，
     *   全部记下来会有几万条，界面只需要计数和几条示例。
     */
    data class MergeResult(
        val merged: BackupEnvelope,
        val addedCount: Int,
        val updatedCount: Int,
        val conflictCount: Int,
        val keptLocalCount: Int,
        val conflicts: List<Conflict>,
    )

    /** 合并结果里最多保留多少条冲突样例。 */
    const val MAX_CONFLICT_SAMPLES: Int = 20

    /** 合并 `incoming` 进 `local`，返回合并后的信封与统计/冲突明细。 */
    @Suppress("LongMethod")
    fun merge(local: BackupEnvelope, incoming: BackupEnvelope): MergeResult {
        val acc = Accumulator()

        val works = acc.mergeTable(
            "works",
            local.works,
            incoming.works,
            { it.id },
            { it.updatedAt },
            { newer, older -> newer.keepFieldsFrom(older) },
        )
        val sourceLinks = acc.mergeTable(
            "source_links",
            local.sourceLinks,
            incoming.sourceLinks,
            { it.id },
            { it.linkedAt },
        )
        val recommendationCounts = acc.mergeTable(
            "recommendation_counts",
            local.recommendationCounts,
            incoming.recommendationCounts,
            { it.workId },
            { it.lastRecommendedAt },
        )
        val backlog = acc.mergeTable(
            "backlog_items",
            local.backlog,
            incoming.backlog,
            { it.workId },
            { it.addedAt },
        )
        val ratings = acc.mergeTable(
            "ratings",
            local.ratings,
            incoming.ratings,
            { it.id },
            { it.fetchedAt },
        )
        val reviews = acc.mergeTable(
            "reviews",
            local.reviews,
            incoming.reviews,
            { it.id },
            { it.createdAt },
        )
        val tags = acc.mergeTable(
            "tags",
            local.tags,
            incoming.tags,
            { it.id },
            { null },
        )
        val workTags = acc.mergeTable(
            "work_tags",
            local.workTags,
            incoming.workTags,
            { "${it.workId}|${it.tagId}" },
            { null },
        )
        val importBatches = acc.mergeTable(
            "import_batches",
            local.importBatches,
            incoming.importBatches,
            { it.id },
            { it.createdAt },
        )
        val importItems = acc.mergeTable(
            "import_items",
            local.importItems,
            incoming.importItems,
            { it.id },
            { null },
        )
        val snapshots = acc.mergeTable(
            "snapshots",
            local.snapshots,
            incoming.snapshots,
            { it.id },
            { it.takenAt },
        )
        val changeLogs = acc.mergeTable(
            "change_logs",
            local.changeLogs,
            incoming.changeLogs,
            { it.id },
            { it.changedAt },
        )
        val tasteProfiles = acc.mergeTable(
            "taste_profiles",
            local.tasteProfiles,
            incoming.tasteProfiles,
            { it.id },
            { it.generatedAt },
        )
        val tasteTagStats = acc.mergeTable(
            "taste_tag_stats",
            local.tasteTagStats,
            incoming.tasteTagStats,
            { it.id },
            { null },
        )
        val userCollections = acc.mergeTable(
            "user_collections",
            local.userCollections,
            incoming.userCollections,
            { it.id },
            { it.updatedAt },
        )
        val workFeatures = acc.mergeTable(
            "work_features",
            local.workFeatures,
            incoming.workFeatures,
            { it.subjectId },
            { it.updatedAt },
            { newer, older -> newer.keepFieldsFrom(older) },
        )
        val tagDimensions = acc.mergeTable(
            "tag_dimensions",
            local.tagDimensions,
            incoming.tagDimensions,
            { it.tag },
            { it.updatedAt },
        )
        val trashItems = acc.mergeTable(
            "trash_items",
            local.trashItems,
            incoming.trashItems,
            { it.id },
            { it.removedAt },
        )

        val merged = local.copy(
            // 头部以本地为准；合并结果绝不携带凭据。
            includesCredentials = false,
            works = works,
            sourceLinks = sourceLinks,
            recommendationCounts = recommendationCounts,
            backlog = backlog,
            ratings = ratings,
            reviews = reviews,
            tags = tags,
            workTags = workTags,
            importBatches = importBatches,
            importItems = importItems,
            snapshots = snapshots,
            changeLogs = changeLogs,
            tasteProfiles = tasteProfiles,
            tasteTagStats = tasteTagStats,
            userCollections = userCollections,
            workFeatures = workFeatures,
            tagDimensions = tagDimensions,
            trashItems = trashItems,
            // Schema 1 did not carry a complete settings snapshot; preserve local values for it.
            settings = if (incoming.schemaVersion >= 2) incoming.settings else local.settings,
            credentials = null,
        )

        return MergeResult(
            merged = merged,
            addedCount = acc.added,
            updatedCount = acc.updated,
            conflictCount = acc.conflictCount,
            keptLocalCount = acc.keptLocalCount,
            conflicts = acc.conflicts,
        )
    }

    /** 可变累加器，承载逐表合并过程中的统计与冲突明细。 */
    private class Accumulator {
        var added: Int = 0
        var updated: Int = 0
        var conflictCount: Int = 0
        var keptLocalCount: Int = 0
        val conflicts: MutableList<Conflict> = mutableListOf()

        fun recordConflict(conflict: Conflict) {
            conflictCount++
            if (conflict.resolution == Resolution.KEPT_LOCAL) keptLocalCount++
            if (conflicts.size < MAX_CONFLICT_SAMPLES) conflicts += conflict
        }

        /**
         * 合并单张表。保持本地原有顺序，新增项追加在末尾，保证结果确定。
         *
         * @param key 业务主键提取器。
         * @param timestamp 用于冲突解决的时间戳提取器；返回 `null` 表示该表无时间戳，冲突时一律保留本地。
         * @param fill 采用导入数据时，用本地数据补上导入数据里缺的字段（旧版本备份没有的字段）。
         */
        @Suppress("LongParameterList") // 表名、两组数据和三个按表不同的取值函数
        fun <T> mergeTable(
            table: String,
            local: List<T>,
            incoming: List<T>,
            key: (T) -> String,
            timestamp: (T) -> Long?,
            fill: (newer: T, older: T) -> T = { newer, _ -> newer },
        ): List<T> {
            // 本地按主键索引（后者覆盖前者，与本地自身一致性无关——仅作查找）。
            val byKey = LinkedHashMap<String, T>(local.size)
            for (item in local) {
                byKey[key(item)] = item
            }

            for (incomingItem in incoming) {
                val k = key(incomingItem)
                val localItem = byKey[k]
                if (localItem == null) {
                    // 本地不存在：新增。
                    byKey[k] = incomingItem
                    added++
                    continue
                }
                // 主键冲突：默认保留较新 updatedAt；无时间戳则保留本地。
                val localTs = timestamp(localItem)
                val incomingTs = timestamp(incomingItem)
                val useIncoming = localTs != null && incomingTs != null && incomingTs > localTs
                if (useIncoming) {
                    byKey[k] = fill(incomingItem, localItem)
                    updated++
                    recordConflict(Conflict(table, k, Resolution.USED_INCOMING))
                } else {
                    // 保留本地，不覆盖。
                    recordConflict(Conflict(table, k, Resolution.KEPT_LOCAL))
                }
            }
            return byKey.values.toList()
        }
    }
}

/** 较旧的备份没有中文名、简介、放送日期：导入它时不要把本地已有的这些字段清空。 */
private fun BackupWork.keepFieldsFrom(older: BackupWork): BackupWork = copy(
    titleCn = titleCn ?: older.titleCn,
    summary = summary ?: older.summary,
    airDate = airDate ?: older.airDate,
)

/** 较旧的备份没有封面和放送日期：导入它时保留本地已有的值。 */
private fun BackupWorkFeature.keepFieldsFrom(older: BackupWorkFeature): BackupWorkFeature = copy(
    coverUrl = coverUrl ?: older.coverUrl,
    airDate = airDate ?: older.airDate,
)
