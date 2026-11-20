package com.acgcompass.data.tastecode

import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.local.dao.RatingDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.taste.TasteAnalysisController
import com.acgcompass.domain.tastecode.TasteCodeBuilder
import com.acgcompass.domain.tastecode.TasteCodeDraft
import com.acgcompass.domain.tastecode.TasteCodeRepository
import com.acgcompass.domain.tastecode.TasteCodeWork
import com.acgcompass.domain.tastecode.TasteCodeWorkInfo
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/** 口味码用到的本地数据：口味画像来自口味引擎，作品与评分来自收藏、作品表和评分表。 */
@Singleton
class TasteCodeRepositoryImpl @Inject constructor(
    private val analysis: TasteAnalysisController,
    private val userCollectionDao: UserCollectionDao,
    private val workDao: WorkDao,
    private val ratingDao: RatingDao,
    private val dispatchers: DispatcherProvider,
) : TasteCodeRepository {

    override suspend fun draft(): TasteCodeDraft? = withContext(dispatchers.io) {
        val profile = analysis.advancedProfile.value
            ?: runCatchingCancellable { analysis.ensureCurrentProfile() }.let { analysis.advancedProfile.value }
            ?: return@withContext null
        if (!profile.isUsable) return@withContext null
        val works = userCollectionDao.getAll()
            .filter { it.source == CredentialSourceId.BANGUMI.name }
            .mapNotNull { record ->
                val subjectId = record.sourceItemId.toIntOrNull() ?: return@mapNotNull null
                TasteCodeWork(
                    subjectId = subjectId,
                    rating = record.rating,
                    communityScore = ratingDao.getByWorkAndSource(record.localWorkId, SourceId.BANGUMI.name)
                        ?.takeIf { !it.missing && it.score > 0f }
                        ?.score?.toDouble(),
                    year = workDao.getById(record.localWorkId)?.year,
                    ratedAt = record.updatedAt,
                )
            }
        val base = TasteCodeBuilder.build(profile, works, today = LocalDate.now().toEpochDay())
        TasteCodeDraft(base = base, candidates = base.works)
    }

    override suspend fun workInfo(subjectIds: Collection<Int>): Map<Int, TasteCodeWorkInfo> = withContext(
        dispatchers.io,
    ) {
        subjectIds.distinct().associateWith { id ->
            val work = workDao.getById(id.toString())
            TasteCodeWorkInfo(id, work?.titleCn ?: work?.canonicalTitle, work?.coverUrl)
        }
    }

    override suspend fun collectedSubjectIds(): Set<Int> = withContext(dispatchers.io) {
        userCollectionDao.getAll().mapNotNull { it.sourceItemId.toIntOrNull() }.toSet()
    }
}
