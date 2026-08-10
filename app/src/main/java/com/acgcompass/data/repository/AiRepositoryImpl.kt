package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.data.local.dao.AiResultDao
import com.acgcompass.data.local.mapper.toDomain
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.domain.model.AiResult
import com.acgcompass.domain.model.AiTaskType
import com.acgcompass.domain.repository.AiRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiRepositoryImpl @Inject constructor(
    private val dao: AiResultDao,
) : AiRepository {
    override suspend fun getCached(workId: String, taskType: AiTaskType): AppResult<AiResult?> =
        runCatchingApp { dao.getLatestForWorkAndTask(workId, taskType.name)?.toDomain() }

    override suspend fun put(result: AiResult) {
        dao.deleteByWorkAndTask(result.workId, result.taskType.name)
        dao.upsert(result.toEntity())
    }
}
