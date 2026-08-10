package com.acgcompass.domain.repository

import com.acgcompass.domain.detail.DetailExtrasData
import kotlinx.coroutines.flow.Flow

interface DetailRepository {
    /** Emits persisted content first, then a changed remote snapshot when a section is stale. */
    fun observeBangumiExtras(workId: String, subjectId: Int, forceRefresh: Boolean = false): Flow<DetailExtrasData>
}
