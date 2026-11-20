package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.TaskRunEntity
import kotlinx.coroutines.flow.Flow

/** 后台任务运行记录。 */
@Dao
interface TaskRunDao {
    @Query("SELECT * FROM task_runs")
    fun observeAll(): Flow<List<TaskRunEntity>>

    @Query("SELECT * FROM task_runs WHERE taskKey = :taskKey")
    suspend fun get(taskKey: String): TaskRunEntity?

    @Upsert
    suspend fun upsert(run: TaskRunEntity)

    /** 进程被杀时「运行中」不会被清掉：启动时统一改回不在运行。 */
    @Query("UPDATE task_runs SET running = 0 WHERE running = 1")
    suspend fun clearRunning()
}
