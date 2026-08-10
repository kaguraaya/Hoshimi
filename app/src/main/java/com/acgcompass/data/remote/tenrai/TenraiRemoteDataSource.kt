package com.acgcompass.data.remote.tenrai

import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.asException
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.core.network.HttpErrorMapper
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.core.network.interceptor.SourceAuths
import com.acgcompass.core.network.interceptor.withPriority
import com.acgcompass.data.remote.jikan.JikanAnimeDto
import com.acgcompass.data.remote.jikan.toRatingEntry
import com.acgcompass.data.remote.jikan.toWork
import com.acgcompass.data.remote.jikan.toWorkMatch
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

@Singleton
class TenraiRemoteDataSource @Inject constructor(
    private val api: TenraiApi,
) {
    private val auth = SourceAuths.tenrai

    suspend fun searchAnime(keyword: String, limit: Int = 10): AppResult<List<WorkMatch>> = runCatchingApp {
        api.searchAnime(keyword, limit, null, true, auth).bodyOrThrow().data.map { dto ->
            dto.toWorkMatch(source = SourceId.TENRAI)
        }
    }

    suspend fun searchAnimeWithRatings(
        keyword: String,
        limit: Int = 10,
        priority: RequestPriority = RequestPriority.FOREGROUND,
    ): AppResult<List<Pair<WorkMatch, RatingEntry?>>> = runCatchingApp {
        api.searchAnime(keyword, limit, null, true, auth.withPriority(priority)).bodyOrThrow().data.map { dto ->
            dto.toWorkMatch(source = SourceId.TENRAI) to dto.toRatingEntry()
        }
    }

    suspend fun getAnime(id: Int): AppResult<Work> = runCatchingApp {
        val anime = api.getAnime(id, auth).bodyOrThrow().data ?: throw AppError.FieldMissing().asException()
        anime.toWork(SourceId.TENRAI)
    }

    suspend fun getAnimeRating(id: Int): AppResult<RatingEntry> = runCatchingApp {
        val anime = api.getAnime(id, auth).bodyOrThrow().data ?: throw AppError.FieldMissing().asException()
        anime.toRatingEntry() ?: throw AppError.FieldMissing().asException()
    }

    suspend fun getTopAnime(
        limit: Int = 25,
        page: Int = 1,
        priority: RequestPriority = RequestPriority.BACKGROUND,
    ): AppResult<List<JikanAnimeDto>> = runCatchingApp {
        api.getTopAnime(page, limit, auth.withPriority(priority)).bodyOrThrow().data
    }

    suspend fun getSeasonNow(
        limit: Int = 25,
        page: Int = 1,
        priority: RequestPriority = RequestPriority.BACKGROUND,
    ): AppResult<List<JikanAnimeDto>> = runCatchingApp {
        api.getSeasonNow(page, limit, auth.withPriority(priority)).bodyOrThrow().data
    }

    private fun <T> Response<T>.bodyOrThrow(): T {
        if (!isSuccessful) throw HttpErrorMapper.mapStatusCode(code()).asException()
        return body() ?: throw AppError.FieldMissing().asException()
    }
}
