package com.acgcompass.domain.repository

import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.model.BackgroundTaskStatus
import kotlinx.coroutines.flow.Flow

/** 后台任务的运行状况与手动重试。 */
interface BackgroundTaskRepository {
    /** 所有任务的状况，按 [BackgroundTaskKind] 的顺序。 */
    fun observeStatuses(): Flow<List<BackgroundTaskStatus>>

    /** 马上再跑一次；任务被关掉或不支持手动重试时返回 false。 */
    suspend fun retry(kind: BackgroundTaskKind): Boolean
}
