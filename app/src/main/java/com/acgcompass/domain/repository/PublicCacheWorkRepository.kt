package com.acgcompass.domain.repository

import com.acgcompass.domain.model.Work
import kotlinx.coroutines.flow.Flow

interface PublicCacheWorkRepository {
    fun observePublicCachedWorks(): Flow<List<Work>>
}
