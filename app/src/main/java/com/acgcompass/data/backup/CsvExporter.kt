package com.acgcompass.data.backup

/**
 * CSV 导出器。把待补池 / 时光机 / 评分表导出为
 * 标准 CSV 文本（RFC 4180 转义：含逗号 / 双引号 / 换行的字段用双引号包裹，内部双引号转义为两个）。
 *
 * **纯函数、无 Android / IO 依赖**：仅做内存中的文本拼装，便于单元测试与复用；落盘由调用方负责。
 *
 * **隐私**：CSV 仅导出业务数据，**绝不**包含任何凭据 / key / token。
 *
 * 备份中的 [BackupSourceLink] 和 [BackupImportBatch] 保留来源与作品关联，
 * 便于导入时合并不同来源的数据。
 */
object CsvExporter {

    /** 待补池 CSV。列：作品ID, 标题, 优先级, 心情标签, 风险标签, 备注, 吃灰天数, 是否吃灰馆。 */
    fun exportBacklog(items: List<BackupBacklogItem>, titlesById: Map<String, String> = emptyMap()): String {
        val header = listOf(
            "作品ID",
            "标题",
            "优先级",
            "心情标签",
            "风险标签",
            "备注",
            "吃灰天数",
            "吃灰馆",
        )
        val rows = items.map { item ->
            listOf(
                item.workId,
                titlesById[item.workId].orEmpty(),
                item.priority,
                item.moodTags.joinToString("、"),
                item.riskTags.joinToString("、"),
                item.note.orEmpty(),
                item.dustDays.toString(),
                if (item.inDustMuseum) "是" else "否",
            )
        }
        return buildCsv(header, rows)
    }

    /** 评分表 CSV。列：作品ID, 来源, 评分, 投票数, 排名, 抓取时间, 缺失。 */
    fun exportRatings(ratings: List<BackupRating>): String {
        val header = listOf("作品ID", "来源", "评分", "投票数", "排名", "抓取时间", "缺失")
        val rows = ratings.map { r ->
            listOf(
                r.workId,
                r.sourceId,
                if (r.missing) "" else r.score.toString(),
                r.voteCount.toString(),
                r.rank?.toString().orEmpty(),
                r.fetchedAt.toString(),
                if (r.missing) "是" else "否",
            )
        }
        return buildCsv(header, rows)
    }

    /** 个人评分 CSV。公共来源评分不混入，避免把聚合评分误认为用户自己的记录。 */
    fun exportPersonalRatings(collections: List<BackupUserCollection>): String {
        val header = listOf("作品ID", "来源", "个人评分", "状态", "短评", "同步时间")
        val rows = collections
            .filter { it.rating != null || !it.comment.isNullOrBlank() }
            .map { collection ->
                listOf(
                    collection.localWorkId,
                    collection.source,
                    collection.rating?.toString().orEmpty(),
                    collection.status.orEmpty(),
                    collection.comment.orEmpty(),
                    collection.syncedAt.toString(),
                )
            }
        return buildCsv(header, rows)
    }

    /** 时光机 CSV 同时包含当前基线、快照和真实变更，确保页面有基线内容时导出不为空。 */
    fun exportTimeMachine(
        changeLogs: List<BackupChangeLog>,
        snapshots: List<BackupSnapshot>,
        collections: List<BackupUserCollection>,
        titlesById: Map<String, String> = emptyMap(),
    ): String {
        val header = listOf("记录类型", "时间", "作品ID", "标题", "变更类型", "字段", "旧值", "新值")
        val changeRows = changeLogs.map { log ->
            TimedCsvRow(
                log.changedAt,
                listOf(
                    "变更",
                    log.changedAt.toString(),
                    log.workId,
                    titlesById[log.workId].orEmpty(),
                    log.changeType,
                    log.field.orEmpty(),
                    log.oldValue.orEmpty(),
                    log.newValue.orEmpty(),
                ),
            )
        }
        val snapshotRows = snapshots.map { snapshot ->
            TimedCsvRow(
                snapshot.takenAt,
                listOf(
                    "快照",
                    snapshot.takenAt.toString(),
                    "",
                    "",
                    snapshot.kind,
                    "快照ID",
                    "",
                    snapshot.id,
                ),
            )
        }
        val baselineRows = collections.map { collection ->
            val summary = listOfNotNull(
                collection.status?.let { "状态=$it" },
                collection.rating?.let { "评分=$it" },
                collection.progress?.let { "进度=$it" },
                collection.comment?.takeIf(String::isNotBlank)?.let { "短评=$it" },
            ).joinToString("；")
            TimedCsvRow(
                collection.updatedAt,
                listOf(
                    "当前基线",
                    collection.updatedAt.toString(),
                    collection.localWorkId,
                    titlesById[collection.localWorkId].orEmpty(),
                    "收藏状态",
                    "当前记录",
                    "",
                    summary,
                ),
            )
        }
        val rows = (changeRows + snapshotRows + baselineRows).sortedByDescending { it.timestamp }.map { it.values }
        return buildCsv(header, rows)
    }

    /** 口味画像标签 CSV，用于核验分类来源、标签权重输入与高低分桶。 */
    fun exportTasteProfile(rows: List<TasteTagCsvRow>): String = buildCsv(
        header = listOf(
            "原始标签",
            "规范化标签",
            "最终维度",
            "分类来源",
            "置信度",
            "分类器版本",
            "处理状态",
            "出现作品数",
            "社区标注总数",
            "高分桶次数",
            "低分桶次数",
            "属于当前个人快照",
            "更新时间",
        ),
        rows = rows.map { row ->
            listOf(
                row.rawTag,
                row.normalizedTag,
                row.dimension,
                row.source,
                row.confidence.toString(),
                row.classifierVersion.toString(),
                row.status,
                row.workCount.toString(),
                row.communityCount.toString(),
                row.highScoreCount.toString(),
                row.lowScoreCount.toString(),
                if (row.inPersonalSnapshot) "是" else "否",
                row.updatedAt.toString(),
            )
        },
    )

    /** 拼装 CSV 文本：表头 + 数据行，行间以 `\n` 连接，字段按 RFC 4180 转义。 */
    private fun buildCsv(header: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append(header.joinToString(",") { escape(it) })
        for (row in rows) {
            sb.append('\n')
            sb.append(row.joinToString(",") { escape(it) })
        }
        return sb.toString()
    }

    /** RFC 4180 字段转义：含逗号 / 双引号 / 换行时用双引号包裹，内部双引号转义为两个双引号。 */
    private fun escape(field: String): String {
        val needsQuote = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuote) return field
        val escaped = field.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    private data class TimedCsvRow(val timestamp: Long, val values: List<String>)
}

data class TasteTagCsvRow(
    val rawTag: String,
    val normalizedTag: String,
    val dimension: String,
    val source: String,
    val confidence: Float,
    val classifierVersion: Int,
    val status: String,
    val workCount: Int,
    val communityCount: Int,
    val highScoreCount: Int,
    val lowScoreCount: Int,
    val inPersonalSnapshot: Boolean,
    val updatedAt: Long,
)
