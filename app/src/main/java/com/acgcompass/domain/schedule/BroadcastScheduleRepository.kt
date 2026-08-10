package com.acgcompass.domain.schedule

import kotlinx.coroutines.flow.Flow

interface BroadcastScheduleRepository {
    fun observeUpcoming(limit: Int = 6): Flow<List<BroadcastScheduleEntry>>

    fun observeWeek(): Flow<WeeklyBroadcastSchedule>

    suspend fun refreshUpcoming(force: Boolean = false): Result<Unit>

    suspend fun refreshWeek(force: Boolean = false): Result<Unit>
}
