package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.data.local.dao.RecommendationExposureDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.mapper.toDomain
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.domain.detail.PersonalRecord
import com.acgcompass.domain.model.RecommendationExposure
import com.acgcompass.domain.repository.BangumiSummarySource
import com.acgcompass.domain.repository.PersonalCollectionRepository
import com.acgcompass.domain.repository.RecommendationExposureRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 个人收藏读取：`user_collections` 表映射成领域模型。 */
@Singleton
class PersonalCollectionRepositoryImpl @Inject constructor(
    private val dao: UserCollectionDao,
    private val dispatchers: DispatcherProvider,
) : PersonalCollectionRepository {
    override fun observeAll(): Flow<List<PersonalRecord>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }.flowOn(dispatchers.io)

    override suspend fun getAll(): List<PersonalRecord> = withContext(dispatchers.io) {
        dao.getAll().map { it.toDomain() }
    }
}

/** 推荐曝光读写：`recommendation_exposures` 表映射成领域模型。 */
@Singleton
class RecommendationExposureRepositoryImpl @Inject constructor(
    private val dao: RecommendationExposureDao,
) : RecommendationExposureRepository {
    override suspend fun exposuresInContextSince(context: String, since: Long): List<RecommendationExposure> =
        dao.exposuresInContextSince(context, since).map { it.toDomain() }

    override suspend fun upsert(exposure: RecommendationExposure) = dao.upsert(exposure.toEntity())

    override suspend fun upsertAll(exposures: List<RecommendationExposure>) =
        dao.upsertAll(exposures.map { it.toEntity() })
}

/** 从 Bangumi 条目接口取简介。 */
@Singleton
class BangumiSummarySourceImpl @Inject constructor(
    private val bangumi: BangumiRemoteDataSource,
) : BangumiSummarySource {
    override suspend fun summary(subjectId: Int): AppResult<String?> = when (val res = bangumi.getSubject(subjectId)) {
        is AppResult.Success -> AppResult.Success(res.data.summary)
        is AppResult.Failure -> res
    }
}
