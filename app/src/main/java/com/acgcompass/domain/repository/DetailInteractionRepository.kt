package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.detail.BangumiRecordUpdate
import com.acgcompass.domain.detail.PersonalRecord
import com.acgcompass.domain.detail.RatedWorkContext
import com.acgcompass.domain.model.Work
import kotlinx.coroutines.flow.Flow

/**
 * Boundary for detail-page interactions that span local records, settings, credentials and Bangumi.
 * UI code depends on this contract; storage and network mapping remain in the data layer.
 */
@Suppress("TooManyFunctions")
interface DetailInteractionRepository {
    fun observePersonalRecord(workId: String): Flow<PersonalRecord?>

    suspend fun personalRecord(workId: String): PersonalRecord?

    suspend fun savePersonalRecord(record: PersonalRecord)

    suspend fun deletePersonalRecord(workId: String)

    suspend fun markPersonalRecordSynced(workId: String, syncedAt: Long)

    suspend fun resolveBangumiSubjectId(work: Work): Int?

    suspend fun ensureBangumiWork(subjectId: Int): AppResult<Unit>

    suspend fun fetchBangumiWork(subjectId: Int): AppResult<Work>

    suspend fun fetchBangumiComments(subjectId: Int, limit: Int): AppResult<List<String>>

    suspend fun updateBangumiRecord(subjectId: Int, update: BangumiRecordUpdate): AppResult<Unit>

    suspend fun bangumiSubjectExists(subjectId: Int): Boolean

    suspend fun markEpisodesWatched(subjectId: Int, watchedCount: Int): AppResult<Unit>

    suspend fun refreshPersonalRecord(work: Work, forceRefresh: Boolean)

    suspend fun ratedWorkContexts(): List<RatedWorkContext>

    suspend fun isBangumiConfigured(): Boolean

    suspend fun isAiConfigured(): Boolean

    suspend fun addRecentlyViewed(workId: String)

    suspend fun shouldAutoTranslateVndbDescription(): Boolean
}
