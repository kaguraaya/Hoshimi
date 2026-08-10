package com.acgcompass.domain.schedule

import java.time.DayOfWeek

data class BroadcastScheduleEntry(
    val scheduleId: String,
    val workId: String,
    val sourceItemId: String,
    val title: String,
    val titleCn: String? = null,
    val titleVariants: List<String> = emptyList(),
    val coverUrl: String? = null,
    val airingAtUtcMillis: Long? = null,
    val dayOfWeek: DayOfWeek,
    val episode: Int? = null,
    val timeToBeDetermined: Boolean = false,
)

data class WeeklyBroadcastSchedule(
    val entriesByDay: Map<DayOfWeek, List<BroadcastScheduleEntry>>,
    val otherSourceEntries: List<BroadcastScheduleEntry> = emptyList(),
    val stale: Boolean = false,
    val refreshing: Boolean = false,
    val errorMessage: String? = null,
) {
    fun entries(day: DayOfWeek): List<BroadcastScheduleEntry> = entriesByDay[day].orEmpty()
}
