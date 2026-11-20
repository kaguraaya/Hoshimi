package com.acgcompass.data.report

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.SnapshotDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.report.ReportPeriod
import com.acgcompass.domain.report.ReportWork
import com.acgcompass.domain.report.WatchReport
import com.acgcompass.domain.report.WatchReportBuilder
import com.acgcompass.domain.report.WatchReportRepository
import com.acgcompass.domain.taste.WorkFeatureRepository
import com.acgcompass.domain.usecase.SnapshotDiff
import com.acgcompass.domain.usecase.TasteTagTaxonomy
import java.time.OffsetDateTime
import javax.inject.Inject
import kotlinx.coroutines.withContext

/**
 * 回顾的数据：本地收藏里状态为「看过」的作品，看完时间按这个顺序取——
 * 时光机记录的「状态变成看过」的时间 → Bangumi 收藏的更新时间 → 本地更新时间。
 * 标题、封面、年份来自作品表，社区评分来自评分表，题材和单集时长来自作品资料（不联网）。
 */
class WatchReportRepositoryImpl @Inject constructor(
    private val userCollectionDao: UserCollectionDao,
    private val snapshotDao: SnapshotDao,
    private val workDao: WorkDao,
    private val ratingDao: RatingDao,
    private val features: WorkFeatureRepository,
    private val dispatchers: DispatcherProvider,
) : WatchReportRepository {

    override suspend fun load(period: ReportPeriod): WatchReport = withContext(dispatchers.io) {
        val collections = userCollectionDao.getAll()
        val statusChanges = snapshotDao.getAllChangeLogs()
            .filter { it.field == SnapshotDiff.FIELD_STATUS }
            .groupBy { it.workId }
        fun changedTo(record: UserCollectionEntity, status: String): Long =
            statusChanges[record.localWorkId]?.filter { it.newValue == status }?.minOfOrNull { it.changedAt }
                ?: record.sourceUpdatedAt?.let(::parseTime)
                ?: record.updatedAt

        val finished = collections.filter { it.status == WATCHED }
            .mapNotNull { record -> changedTo(record, WATCHED).takeIf { period.contains(it) }?.let { record to it } }
        val dropped = collections.count { it.status == DROPPED && period.contains(changedTo(it, DROPPED)) }
        val featureMap = features.getFeatures(
            finished.map {
                it.first.sourceItemId.ifBlank { it.first.localWorkId }
            },
            networkBudget = 0,
        )
        val works = finished.map { (record, at) ->
            val work = workDao.getById(record.localWorkId)
            val feature = featureMap[record.sourceItemId.ifBlank { record.localWorkId }]
            ReportWork(
                workId = record.localWorkId,
                title = work?.titleCn ?: work?.canonicalTitle ?: feature?.titleVariants?.firstOrNull()
                    ?: "条目 ${record.localWorkId}",
                coverUrl = work?.coverUrl ?: feature?.coverUrl,
                rating = record.rating?.takeIf { it > 0 },
                community = ratingDao.getByWorkAndSource(record.localWorkId, SourceId.BANGUMI.name)
                    ?.takeIf { !it.missing && it.score > 0f }?.score?.toDouble(),
                year = work?.year ?: feature?.airDate?.take(YEAR_DIGITS)?.toIntOrNull(),
                episodes = feature?.eps ?: 0,
                minutesPerEpisode = feature?.durationMin?.takeIf { it > 0 },
                finishedAt = at,
                genres = feature?.tagCounts.orEmpty()
                    .sortedByDescending { it.count }
                    .map { it.name }
                    .filter(TasteTagTaxonomy::isSelectableGenre),
            )
        }
        WatchReportBuilder.build(period, works, dropped)
    }

    private fun parseTime(value: String): Long? = runCatchingCancellable {
        OffsetDateTime.parse(value).toInstant().toEpochMilli()
    }.getOrNull()

    private companion object {
        const val WATCHED = "看过"
        const val DROPPED = "抛弃"
        const val YEAR_DIGITS = 4
    }
}
