package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.data.remote.anilist.AniListRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.mal.MalRemoteDataSource
import com.acgcompass.data.remote.tenrai.TenraiRemoteDataSource
import com.acgcompass.data.remote.vndb.VndbRemoteDataSource
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import javax.inject.Inject
import javax.inject.Singleton

/** 按来源拉取单个平台的评分。 */
@Singleton
class SourceRatingFetcher @Inject constructor(
    private val bangumi: BangumiRemoteDataSource,
    private val anilist: AniListRemoteDataSource,
    private val tenrai: TenraiRemoteDataSource,
    private val mal: MalRemoteDataSource,
    private val vndb: VndbRemoteDataSource,
) {
    /** 按来源拉取单源评分；不支持 / 未启用 / id 非法时返回 `null`。 */
    suspend fun fetch(
        sid: SourceId,
        itemId: String,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<com.acgcompass.domain.model.RatingEntry>? = when (sid) {
        SourceId.BANGUMI -> itemId.toIntOrNull()?.let { bangumi.getSubjectRating(it, priority) }
        SourceId.ANILIST -> itemId.toIntOrNull()?.let { anilist.getMediaRating(it, priority) }
        SourceId.TENRAI -> itemId.toIntOrNull()?.let { tenrai.getAnimeRating(it, priority) }
        SourceId.JIKAN -> null
        SourceId.MAL -> if (mal.isEnabled()) itemId.toIntOrNull()?.let { mal.getAnimeRating(it, priority) } else null
        SourceId.VNDB -> vndb.getVnRating(itemId)
    }
}
