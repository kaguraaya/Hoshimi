package com.acgcompass.domain.repository

import com.acgcompass.domain.detail.PersonalRecord
import kotlinx.coroutines.flow.Flow

/** 个人收藏（看过 / 在看 / 想看 / 评分等）的读取。页面通过它读收藏，不直接碰数据库。 */
interface PersonalCollectionRepository {
    fun observeAll(): Flow<List<PersonalRecord>>

    suspend fun getAll(): List<PersonalRecord>
}

/** 推荐曝光记录的读写。 */
interface RecommendationExposureRepository {
    suspend fun exposuresInContextSince(
        context: String,
        since: Long,
    ): List<com.acgcompass.domain.model.RecommendationExposure>

    suspend fun upsert(exposure: com.acgcompass.domain.model.RecommendationExposure)

    suspend fun upsertAll(exposures: List<com.acgcompass.domain.model.RecommendationExposure>)
}

/**
 * 吃灰馆与收藏状态的联动：移入吃灰馆时本地标记「搁置」并回写 Bangumi，移出时还原。
 * 失败只忽略，不影响待补池本身的操作。
 */
interface DustStatusSync {
    suspend fun archive(workId: String)

    suspend fun restore(workId: String)
}

/** 从 Bangumi 取作品简介（探索队列卡片背面用）。查到但没有简介时返回 Success(null)。 */
interface BangumiSummarySource {
    suspend fun summary(subjectId: Int): com.acgcompass.core.common.AppResult<String?>
}
