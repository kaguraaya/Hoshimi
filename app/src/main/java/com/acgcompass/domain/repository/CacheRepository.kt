package com.acgcompass.domain.repository

import com.acgcompass.domain.cache.CacheClearRequest
import com.acgcompass.domain.cache.CacheFillRun
import com.acgcompass.domain.cache.CacheItem
import com.acgcompass.domain.cache.CachePolicy
import com.acgcompass.domain.cache.CacheTrimResult
import com.acgcompass.domain.cache.CacheUsage
import com.acgcompass.domain.cache.ManagedMediaRecord
import kotlinx.coroutines.flow.Flow

interface CacheRepository {
    fun observeUsage(): Flow<CacheUsage>

    fun observeItems(): Flow<List<CacheItem>>

    fun observeFillRuns(): Flow<List<CacheFillRun>>

    suspend fun currentPolicy(): CachePolicy

    suspend fun trimToPolicy(): CacheTrimResult = trimToPolicy(currentPolicy())

    suspend fun trimToPolicy(policy: CachePolicy): CacheTrimResult

    suspend fun clear(request: CacheClearRequest): CacheTrimResult

    suspend fun clearAllPublicCache(): CacheTrimResult

    suspend fun recordMedia(record: ManagedMediaRecord)
}
