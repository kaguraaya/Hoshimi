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

    /**
     * 把本人记录移进本地回收站（同一次操作共用 [batchId]），没有记录时返回 false。
     * 不改动 Bangumi：公开接口不能删除收藏，网页上的记录由用户自己移除。
     */
    suspend fun trashPersonalRecord(workId: String, batchId: String): Boolean

    /** 推送失败的修改排进待推送队列，联网后自动重试。 */
    fun schedulePendingPush()

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

    /**
     * 「隐私与数据 → 允许把短评发给 AI」是否开启。关闭（默认）时，任何评论内容——你自己的短评和
     * 社区评论——都不得出现在发给 AI 提供商的请求里。
     */
    suspend fun isAiReviewAnalysisAllowed(): Boolean

    suspend fun addRecentlyViewed(workId: String)

    suspend fun shouldAutoTranslateVndbDescription(): Boolean
}
