package com.acgcompass.data.taste

import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.local.dao.PredictionSnapshotDao
import com.acgcompass.data.local.dao.ReconciledPredictionRow
import com.acgcompass.data.local.entity.PredictionSnapshotEntity
import com.acgcompass.domain.taste.LedgerEntry
import com.acgcompass.domain.taste.PredictionContext
import java.time.OffsetDateTime
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 预测账本：在用户看到预测的时候（详情页、探索卡片、加入待补池）记下当时的预计评分，
 * 等用户真的打了分再对账。只写本地数据库，不联网，失败不影响页面。
 */
@Singleton
class PredictionLedgerRecorder @Inject constructor(
    private val dao: PredictionSnapshotDao,
    private val tasteEngine: TasteEngine,
) {
    private val writesSinceTrim = AtomicInteger(0)

    suspend fun record(
        context: PredictionContext,
        subjectIds: Collection<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        runCatchingCancellable {
            val main = tasteEngine.currentProfile?.divergence?.modelVersion ?: return@runCatchingCancellable
            val ids = subjectIds.filter { it.isNotBlank() }.distinct()
            if (ids.isEmpty()) return@runCatchingCancellable
            val already = dao.recordedAmong(main, ids).toSet()
            val fresh = ids.filter { it !in already }
            if (fresh.isEmpty()) return@runCatchingCancellable
            val rows = tasteEngine.predictForLedger(fresh).map { p ->
                PredictionSnapshotEntity(
                    workId = p.subjectId,
                    modelVersion = p.modelVersion,
                    context = context.name,
                    createdAt = now,
                    predictedRating = p.match.prediction?.rating,
                    lowRating = p.match.prediction?.low,
                    highRating = p.match.prediction?.high,
                    matchScore = p.match.score,
                    satisfaction = p.match.satisfaction.takeIf { p.match.prediction != null },
                    profileSize = p.profileSize,
                )
            }
            if (rows.isEmpty()) return@runCatchingCancellable
            dao.insertAll(rows)
            if (writesSinceTrim.addAndGet(rows.size) >= TRIM_EVERY) {
                writesSinceTrim.set(0)
                dao.trimTo(MAX_ROWS)
            }
        }
    }

    /** 已经有实际评分、且评分晚于预测的记录。 */
    suspend fun reconciled(): List<LedgerEntry> =
        runCatchingCancellable { dao.reconciled().filter(::ratedAfterPrediction).map { it.toEntry() } }
            .getOrDefault(emptyList())

    suspend fun count(): Int = runCatchingCancellable { dao.count() }.getOrDefault(0)

    private fun ratedAfterPrediction(row: ReconciledPredictionRow): Boolean {
        val ratedAt = row.ratedAtIso?.let { iso ->
            runCatchingCancellable { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        } ?: row.ratedAt
        return ratedAt > row.createdAt
    }

    private fun ReconciledPredictionRow.toEntry() = LedgerEntry(
        workId = workId,
        modelVersion = modelVersion,
        context = PredictionContext.entries.firstOrNull { it.name == context },
        predicted = predictedRating,
        low = lowRating,
        high = highRating,
        matchScore = matchScore,
        actual = actualRating,
        profileSize = profileSize,
    )

    private companion object {
        /** 账本最多留这么多条（每部作品每个版本一条，足够覆盖很长时间）。 */
        const val MAX_ROWS = 20_000

        const val TRIM_EVERY = 200
    }
}
