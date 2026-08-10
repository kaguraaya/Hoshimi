package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiUserSubjectCollectionDto
import com.acgcompass.domain.detail.BangumiRecordUpdate
import com.acgcompass.domain.detail.DetailCacheTtl
import com.acgcompass.domain.detail.PersonalRecord
import com.acgcompass.domain.detail.RatedWorkContext
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.repository.DetailInteractionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

@Singleton
@Suppress("TooManyFunctions")
class DetailInteractionRepositoryImpl @Inject constructor(
    private val userCollectionDao: UserCollectionDao,
    private val workDao: WorkDao,
    private val sourceLinkDao: SourceLinkDao,
    private val bangumi: BangumiRemoteDataSource,
    private val credentialStore: CredentialStore,
    private val settings: SettingsDataStore,
) : DetailInteractionRepository {
    override fun observePersonalRecord(workId: String): Flow<PersonalRecord?> =
        userCollectionDao.observeByWork(workId).map { it?.toDomain() }

    override suspend fun personalRecord(workId: String): PersonalRecord? =
        userCollectionDao.getByWork(workId)?.toDomain()

    override suspend fun savePersonalRecord(record: PersonalRecord) {
        userCollectionDao.upsert(record.toEntity())
    }

    override suspend fun deletePersonalRecord(workId: String) {
        userCollectionDao.deleteByWork(workId)
    }

    override suspend fun markPersonalRecordSynced(workId: String, syncedAt: Long) {
        userCollectionDao.getByWork(workId)?.let { userCollectionDao.upsert(it.copy(syncedAt = syncedAt)) }
    }

    override suspend fun resolveBangumiSubjectId(work: Work): Int? {
        if (work.primarySource == SourceId.BANGUMI) return work.id.toIntOrNull()
        return sourceLinkDao.getByWork(work.id)
            .firstOrNull { it.sourceId == SourceId.BANGUMI.name }
            ?.sourceItemId
            ?.toIntOrNull()
    }

    override suspend fun ensureBangumiWork(subjectId: Int): AppResult<Unit> {
        val localId = subjectId.toString()
        if (workDao.getById(localId) != null) return AppResult.Success(Unit)
        return when (val fetched = bangumi.getSubject(subjectId)) {
            is AppResult.Failure -> fetched
            is AppResult.Success -> {
                val now = System.currentTimeMillis()
                workDao.upsert(fetched.data.toEntity(createdAt = now, updatedAt = now))
                AppResult.Success(Unit)
            }
        }
    }

    override suspend fun fetchBangumiWork(subjectId: Int): AppResult<Work> = bangumi.getSubject(subjectId)

    override suspend fun fetchBangumiComments(subjectId: Int, limit: Int): AppResult<List<String>> =
        when (val result = bangumi.getSubjectComments(subjectId, limit)) {
            is AppResult.Failure -> result
            is AppResult.Success -> AppResult.Success(result.data.map { it.text })
        }

    override suspend fun updateBangumiRecord(subjectId: Int, update: BangumiRecordUpdate): AppResult<Unit> =
        bangumi.updateUserCollection(
            subjectId = subjectId,
            type = update.type,
            rate = update.rating,
            comment = update.comment,
            tags = update.tags,
            private = update.isPrivate,
        )

    override suspend fun bangumiSubjectExists(subjectId: Int): Boolean = bangumi.subjectExists(subjectId)

    override suspend fun markEpisodesWatched(subjectId: Int, watchedCount: Int): AppResult<Unit> =
        bangumi.markEpisodesWatched(subjectId, watchedCount)

    @Suppress("ReturnCount")
    override suspend fun refreshPersonalRecord(work: Work, forceRefresh: Boolean) {
        val subjectId = resolveBangumiSubjectId(work) ?: return
        if (!isBangumiConfigured()) return
        val username = settings.bangumiUsername.first()?.takeIf(String::isNotBlank) ?: return
        val current = userCollectionDao.getByWork(work.id)
        val now = System.currentTimeMillis()
        if (!forceRefresh && current != null && now - current.syncedAt < DetailCacheTtl.PERSONAL_MILLIS) return
        val remote = bangumi.getUserSubjectCollection(username, subjectId)
        val item = (remote as? AppResult.Success)?.data ?: return
        userCollectionDao.upsert(item.toEntity(work.id, subjectId, current, now))
    }

    override suspend fun ratedWorkContexts(): List<RatedWorkContext> = userCollectionDao.getAll()
        .mapNotNull { record ->
            val rating = record.rating ?: return@mapNotNull null
            val title = workDao.getById(record.localWorkId)?.canonicalTitle?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            RatedWorkContext(title, rating, record.comment, record.updatedAt)
        }

    override suspend fun isBangumiConfigured(): Boolean =
        credentialStore.observeStatus().first()[CredentialSourceId.BANGUMI]?.configured == true

    override suspend fun isAiConfigured(): Boolean =
        credentialStore.observeStatus().first()[CredentialSourceId.AI_PROVIDER]?.configured == true

    override suspend fun addRecentlyViewed(workId: String) = settings.addRecentlyViewed(workId)

    override suspend fun shouldAutoTranslateVndbDescription(): Boolean {
        return settings.autoTranslateVndbDescription.first()
    }
}

private fun UserCollectionEntity.toDomain() = PersonalRecord(
    id = id,
    source = source,
    sourceItemId = sourceItemId,
    workId = localWorkId,
    status = status,
    rating = rating,
    progress = progress,
    comment = comment,
    tags = tags,
    updatedAt = updatedAt,
    syncedAt = syncedAt,
    sourceUpdatedAt = sourceUpdatedAt,
    isPrivate = isPrivate,
)

private fun PersonalRecord.toEntity() = UserCollectionEntity(
    id = id,
    source = source,
    sourceItemId = sourceItemId,
    localWorkId = workId,
    status = status,
    rating = rating,
    progress = progress,
    comment = comment,
    tags = tags,
    updatedAt = updatedAt,
    syncedAt = syncedAt,
    sourceUpdatedAt = sourceUpdatedAt,
    isPrivate = isPrivate,
)

private fun BangumiUserSubjectCollectionDto.toEntity(
    workId: String,
    subjectId: Int,
    current: UserCollectionEntity?,
    now: Long,
) = UserCollectionEntity(
    id = "BANGUMI:$subjectId",
    source = SourceId.BANGUMI.name,
    sourceItemId = subjectId.toString(),
    localWorkId = workId,
    status = collectionStatus(type, current?.status),
    rating = rate.takeIf { it > 0 },
    progress = epStatus.takeIf { it > 0 },
    comment = comment?.takeIf(String::isNotBlank),
    tags = tags,
    updatedAt = current?.updatedAt ?: now,
    syncedAt = now,
    sourceUpdatedAt = updatedAt,
    isPrivate = private,
)

@Suppress("MagicNumber")
private fun collectionStatus(type: Int?, fallback: String?): String? = when (type) {
    1 -> "想看"
    2 -> "看过"
    3 -> "在看"
    4 -> "搁置"
    5 -> "抛弃"
    else -> fallback
}
