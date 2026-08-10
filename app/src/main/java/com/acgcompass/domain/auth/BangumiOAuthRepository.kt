package com.acgcompass.domain.auth

import com.acgcompass.core.common.AppResult

data class BangumiAuthorization(
    val authorizeUrl: String,
    val state: String,
)

interface BangumiOAuthRepository {
    fun isAvailable(): Boolean

    suspend fun buildAuthorization(state: String): AppResult<BangumiAuthorization>

    suspend fun exchangeCodeAndPersist(code: String, state: String): AppResult<Unit>
}
