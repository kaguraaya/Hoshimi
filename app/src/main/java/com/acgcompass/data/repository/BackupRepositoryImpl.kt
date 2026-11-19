package com.acgcompass.data.repository

import androidx.room.withTransaction
import com.acgcompass.BuildConfig
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.data.backup.BackupEnvelope
import com.acgcompass.data.backup.BackupMerger
import com.acgcompass.data.backup.BackupSerializer
import com.acgcompass.data.backup.BackupSettings
import com.acgcompass.data.backup.CsvExporter
import com.acgcompass.data.backup.RatedWorkCsvRow
import com.acgcompass.data.backup.TasteTagCsvRow
import com.acgcompass.data.backup.toBackup
import com.acgcompass.data.backup.toEntity
import com.acgcompass.data.backup.toSettingsState
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.RedactedCredentials
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.dao.BacklogDao
import com.acgcompass.data.local.dao.ImportDao
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.SnapshotDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.TagDao
import com.acgcompass.data.local.dao.TagDimensionDao
import com.acgcompass.data.local.dao.TasteDao
import com.acgcompass.data.local.dao.TrashDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.dao.WorkFeatureDao
import com.acgcompass.domain.repository.BackupRepository
import com.acgcompass.domain.repository.CsvKind
import com.acgcompass.domain.repository.ImportConflict
import com.acgcompass.domain.repository.ImportReport
import com.acgcompass.domain.taste.TagClassifier
import com.acgcompass.domain.taste.TasteCategory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [BackupRepository] 实现。
 *
 * **默认零凭据（CRITICAL）**：[exportBackup] 缺省 `includeCredentials=false`，输出信封
 * `includesCredentials=false`、`credentials=null`，绝不含任何 key/token/secret 明文
 * 。仅当显式传 `true`（更高层二次确认后）才附带**脱敏**凭据，经
 * [CredentialStore.exportRedacted] 掩码处理。
 *
 * **导入合并不覆盖**：[importBackup] 反序列化后交由纯函数 [BackupMerger.merge] 与当前本地数据按
 * 业务主键合并——本地不存在则新增，冲突默认保留较新 `updatedAt`，绝不覆盖更新的本地数据；
 * 合并结果再整表 upsert 落库。设置与凭据不被导入覆盖。
 *
 * 读写均经 DAO（works/backlog/ratings/tags/import/snapshot/taste 等）；凭据明文全程不进入本类的
 * 序列化路径。
 */
@Singleton
@Suppress("LongParameterList")
class BackupRepositoryImpl @Inject constructor(
    private val workDao: WorkDao,
    private val sourceLinkDao: SourceLinkDao,
    private val ratingDao: RatingDao,
    private val backlogDao: BacklogDao,
    private val tagDao: TagDao,
    private val importDao: ImportDao,
    private val snapshotDao: SnapshotDao,
    private val tasteDao: TasteDao,
    private val userCollectionDao: UserCollectionDao,
    private val workFeatureDao: WorkFeatureDao,
    private val tagDimensionDao: TagDimensionDao,
    private val trashDao: TrashDao,
    private val database: AcgCompassDatabase,
    private val settingsDataStore: SettingsDataStore,
    private val credentialStore: CredentialStore,
    private val dispatchers: DispatcherProvider,
) : BackupRepository {

    // --- exportBackup  -------------------------------------------

    override suspend fun exportBackup(includeCredentials: Boolean): AppResult<String> = withContext(dispatchers.io) {
        runCatchingApp {
            val envelope = readLocalEnvelope(includeCredentials)
            BackupSerializer.serialize(envelope)
        }
    }

    /**
     * 从各 DAO + 设置读出当前本地业务数据，组装为备份信封。默认不含凭据。
     * 各表在同一个数据库事务里读，导出时即使同步或清理在写库，备份里的各表也来自同一时刻。
     */
    private suspend fun readLocalEnvelope(includeCredentials: Boolean): BackupEnvelope {
        val settings = settingsDataStore.settings.first()
        // 仅在显式要求时附带脱敏凭据；否则恒为 null。
        val redacted = if (includeCredentials) credentialStore.exportRedacted() else null

        return database.withTransaction { readTables(settings.toBackup(), redacted, includeCredentials) }
    }

    private suspend fun readTables(
        settings: BackupSettings,
        redacted: RedactedCredentials?,
        includeCredentials: Boolean,
    ): BackupEnvelope = BackupEnvelope(
        schemaVersion = BackupEnvelope.CURRENT_SCHEMA_VERSION,
        exportedAt = System.currentTimeMillis(),
        appVersion = BuildConfig.VERSION_NAME,
        includesCredentials = includeCredentials,
        works = workDao.getAll().map { it.toBackup() },
        sourceLinks = sourceLinkDao.getAll().map { it.toBackup() },
        recommendationCounts = workDao.getAllRecommendationCounts().map { it.toBackup() },
        backlog = backlogDao.getAll().map { it.toBackup() },
        ratings = ratingDao.getAll().map { it.toBackup() },
        reviews = emptyList(),
        tags = tagDao.getAll().map { it.toBackup() },
        workTags = workDao.getAllWorkTags().map { it.toBackup() },
        importBatches = importDao.getAllBatches().map { it.toBackup() },
        importItems = importDao.getAllItems().map { it.toBackup() },
        snapshots = snapshotDao.getAllSnapshots().map { it.toBackup() },
        changeLogs = snapshotDao.getAllChangeLogs().map { it.toBackup() },
        tasteProfiles = tasteDao.getAllProfiles().map { it.toBackup() },
        tasteTagStats = tasteDao.getAllTagStats().map { it.toBackup() },
        userCollections = userCollectionDao.getAll().map { it.toBackup() },
        workFeatures = workFeatureDao.getAll(limit = Int.MAX_VALUE).map { it.toBackup() },
        tagDimensions = tagDimensionDao.getAll().map { it.toBackup() },
        trashItems = trashDao.getAll().map { it.toBackup() },
        settings = settings,
        credentials = redacted,
    )

    // --- importBackup (merge-not-overwrite) -----------------------

    override suspend fun importBackup(json: String): AppResult<ImportReport> = withContext(dispatchers.io) {
        runCatchingApp {
            val incoming = BackupSerializer.deserialize(json)
            // 读出当前本地数据（不含凭据）作为合并基线。
            val local = readLocalEnvelope(includeCredentials = false)
            val result = BackupMerger.merge(local = local, incoming = incoming)

            database.withTransaction {
                persist(result.merged)
                settingsDataStore.replaceSettings(result.merged.settings.toSettingsState())
            }

            ImportReport(
                added = result.addedCount,
                updated = result.updatedCount,
                conflicts = result.conflicts.map { conflict ->
                    ImportConflict(
                        table = conflict.table,
                        key = conflict.key,
                        keptLocal = conflict.resolution == BackupMerger.Resolution.KEPT_LOCAL,
                    )
                },
            )
        }
    }

    // --- CSV export ----------------------------------------------

    override suspend fun exportCsv(kind: CsvKind): AppResult<String> = withContext(dispatchers.io) {
        runCatchingApp {
            val envelope = readLocalEnvelope(includeCredentials = false)
            when (kind) {
                CsvKind.BACKLOG -> {
                    val titles = envelope.works.associate { it.id to it.canonicalTitle }
                    CsvExporter.exportBacklog(envelope.backlog, titles)
                }
                CsvKind.RATINGS -> {
                    val titles = envelope.works.associate { it.id to it.canonicalTitle }
                    CsvExporter.exportPersonalRatings(envelope.userCollections, titles)
                }
                CsvKind.RATED_WORKS -> CsvExporter.exportRatedWorks(envelope.toRatedWorkCsvRows())
                CsvKind.TIME_MACHINE -> {
                    val titles = envelope.works.associate { it.id to it.canonicalTitle }
                    CsvExporter.exportTimeMachine(
                        envelope.changeLogs,
                        envelope.snapshots,
                        envelope.userCollections,
                        titles,
                    )
                }
                CsvKind.TASTE_PROFILE -> CsvExporter.exportTasteProfile(buildTasteTagRows(envelope))
            }
        }
    }

    private fun buildTasteTagRows(envelope: BackupEnvelope): List<TasteTagCsvRow> {
        val personalIds = personalTasteWorkIds(envelope)
        val dimensions = envelope.tagDimensions
            .filter { it.valid }
            .associateBy { TagClassifier.clean(it.tag) }
        val bucketCounts = envelope.tasteTagStats.groupBy { TagClassifier.clean(it.tagName) }
        val aggregates = aggregateTasteTags(envelope, personalIds)
        return aggregates.map { (normalized, aggregate) ->
            aggregate.toCsvRow(normalized, dimensions[normalized], bucketCounts[normalized].orEmpty())
        }.sortedWith(
            compareByDescending<TasteTagCsvRow> { it.inPersonalSnapshot }
                .thenByDescending { it.communityCount },
        )
    }

    private fun personalTasteWorkIds(envelope: BackupEnvelope): Set<String> = buildSet {
        envelope.userCollections.forEach { add(it.sourceItemId.ifBlank { it.localWorkId }) }
        envelope.backlog.forEach { add(it.workId) }
    }

    private fun aggregateTasteTags(
        envelope: BackupEnvelope,
        personalIds: Set<String>,
    ): Map<String, TasteTagAggregate> {
        val aggregates = linkedMapOf<String, TasteTagAggregate>()
        envelope.workFeatures.forEach { feature ->
            val context = TagClassifier.Context.of(
                staff = feature.staff,
                characters = feature.characters,
                cv = feature.cv,
                titles = feature.titles,
            )
            parseFeatureTags(feature.tagCountsJson).forEach { (rawTag, count) ->
                val normalized = TagClassifier.clean(rawTag)
                if (normalized.isBlank()) return@forEach
                val aggregate = aggregates.getOrPut(normalized) { TasteTagAggregate(rawTag) }
                aggregate.workIds += feature.subjectId
                aggregate.communityCount += count.coerceAtLeast(0)
                aggregate.localCategories += TagClassifier.classify(rawTag, context)
                aggregate.inPersonalSnapshot = aggregate.inPersonalSnapshot || feature.subjectId in personalIds
                aggregate.updatedAt = maxOf(aggregate.updatedAt, feature.updatedAt)
            }
        }
        return aggregates
    }

    private fun TasteTagAggregate.toCsvRow(
        normalized: String,
        override: com.acgcompass.data.backup.BackupTagDimension?,
        buckets: List<com.acgcompass.data.backup.BackupTasteTagStat>,
    ): TasteTagCsvRow {
        val category = override?.let { TasteCategory.fromKey(it.dimension) }
            ?: localCategories.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            ?: TasteCategory.NOISE
        return TasteTagCsvRow(
            rawTag = rawTag,
            normalizedTag = normalized,
            dimension = category.label,
            source = override?.source ?: "LOCAL_RULE",
            confidence = override?.confidence ?: 1f,
            classifierVersion = override?.classifierVersion ?: 0,
            status = if (category == TasteCategory.NOISE) "REJECTED" else "CLASSIFIED",
            workCount = workIds.size,
            communityCount = communityCount,
            highScoreCount = buckets.filter { it.bucket == "HIGH_SCORE" }.sumOf { it.count },
            lowScoreCount = buckets.filter { it.bucket == "LOW_SCORE" }.sumOf { it.count },
            inPersonalSnapshot = inPersonalSnapshot,
            updatedAt = maxOf(updatedAt, override?.updatedAt ?: 0L),
        )
    }

    private data class TasteTagAggregate(
        val rawTag: String,
        val workIds: MutableSet<String> = linkedSetOf(),
        var communityCount: Int = 0,
        val localCategories: MutableList<TasteCategory> = mutableListOf(),
        var inPersonalSnapshot: Boolean = false,
        var updatedAt: Long = 0L,
    )

    /**
     * 将合并后的信封整表 upsert 落库。仅写入业务数据；设置保持本地、凭据绝不写入（合并结果已保证
     * `credentials=null`）。
     */
    private suspend fun persist(envelope: BackupEnvelope) {
        workDao.upsertAll(envelope.works.map { it.toEntity() })
        envelope.recommendationCounts.forEach { workDao.upsertRecommendationCount(it.toEntity()) }
        workDao.upsertWorkTags(envelope.workTags.map { it.toEntity() })
        sourceLinkDao.upsertAll(envelope.sourceLinks.map { it.toEntity() })
        ratingDao.upsertAll(envelope.ratings.map { it.toEntity() })
        backlogDao.upsertAll(envelope.backlog.map { it.toEntity() })
        tagDao.upsertAll(envelope.tags.map { it.toEntity() })
        envelope.importBatches.forEach { importDao.upsertBatch(it.toEntity()) }
        importDao.upsertItems(envelope.importItems.map { it.toEntity() })
        envelope.snapshots.forEach { snapshotDao.upsertSnapshot(it.toEntity()) }
        snapshotDao.upsertChangeLogs(envelope.changeLogs.map { it.toEntity() })
        envelope.tasteProfiles.forEach { tasteDao.upsertProfile(it.toEntity()) }
        tasteDao.upsertTagStats(envelope.tasteTagStats.map { it.toEntity() })
        userCollectionDao.upsertAll(envelope.userCollections.map { it.toEntity() })
        workFeatureDao.upsertAll(envelope.workFeatures.map { it.toEntity() })
        tagDimensionDao.upsertAll(envelope.tagDimensions.map { it.toEntity() })
        trashDao.upsertAll(envelope.trashItems.map { it.toEntity() })
    }
}

internal fun BackupEnvelope.toRatedWorkCsvRows(): List<RatedWorkCsvRow> {
    val worksById = works.associateBy { it.id }
    val tagNamesById = tags.associate { it.id to it.name }
    val tagsByWork = linkedMapOf<String, MutableSet<String>>()

    workTags.forEach { link ->
        val name = tagNamesById[link.tagId]?.trim()?.takeIf(String::isNotEmpty) ?: return@forEach
        tagsByWork.getOrPut(link.workId) { linkedSetOf() }.add(name)
    }
    workFeatures.forEach { feature ->
        val workTagNames = tagsByWork.getOrPut(feature.subjectId) { linkedSetOf() }
        parseFeatureTags(feature.tagCountsJson).forEach { (name, _) ->
            name.trim().takeIf(String::isNotEmpty)?.let(workTagNames::add)
        }
    }

    return userCollections.mapNotNull { collection ->
        val rating = collection.rating ?: return@mapNotNull null
        val workId = collection.localWorkId
        val work = worksById[workId] ?: worksById[collection.sourceItemId]
        RatedWorkCsvRow(
            workId = workId,
            title = work?.canonicalTitle?.takeIf(String::isNotBlank) ?: workId,
            rating = rating,
            sourceUpdatedAt = collection.sourceUpdatedAt?.takeIf(String::isNotBlank),
            tags = (tagsByWork[workId].orEmpty() + tagsByWork[collection.sourceItemId].orEmpty()).distinct(),
        )
    }
}

private fun parseFeatureTags(json: String): List<Pair<String, Int>> = runCatching {
    Json.parseToJsonElement(json).jsonArray.mapNotNull { element ->
        val value = element.jsonObject
        val name = value["name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        name to (value["count"]?.jsonPrimitive?.intOrNull ?: 0)
    }
}.getOrDefault(emptyList())
