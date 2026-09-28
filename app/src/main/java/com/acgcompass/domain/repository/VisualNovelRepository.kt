package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.domain.visualnovel.VisualNovelDetail
import com.acgcompass.domain.visualnovel.VisualNovelDetailSnapshot
import com.acgcompass.domain.visualnovel.VisualNovelPage
import com.acgcompass.domain.visualnovel.VisualNovelSort

interface VisualNovelRepository {
    suspend fun featured(
        sort: VisualNovelSort = VisualNovelSort.RATING,
        page: Int = 1,
        pageSize: Int = 20,
        forceRefresh: Boolean = false,
        randomSeed: Long = 0L,
    ): AppResult<VisualNovelPage>

    suspend fun search(
        query: String,
        page: Int = 1,
        pageSize: Int = 20,
        forceRefresh: Boolean = false,
    ): AppResult<VisualNovelPage>

    suspend fun detail(id: String, forceRefresh: Boolean = false): AppResult<VisualNovelDetail>

    suspend fun cachedDetail(id: String): VisualNovelDetailSnapshot?

    /** 缓存的详情还没关联 Bangumi 时补一次匹配；匹配到返回更新后的详情，否则 null。 */
    suspend fun linkBangumiIfMissing(detail: VisualNovelDetail): VisualNovelDetail? = null

    suspend fun producerWorks(
        producerId: String,
        page: Int = 1,
        pageSize: Int = 20,
        forceRefresh: Boolean = false,
    ): AppResult<VisualNovelPage>
}
