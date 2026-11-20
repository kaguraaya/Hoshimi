package com.acgcompass.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.acgcompass.data.local.entity.RatingBackfillStateEntity

/** 后台补评分的逐部记录与候选查询。 */
@Dao
interface RatingBackfillDao {
    @Query("SELECT * FROM rating_backfill_state WHERE workId = :workId")
    suspend fun get(workId: String): RatingBackfillStateEntity?

    @Upsert
    suspend fun upsert(state: RatingBackfillStateEntity)

    @Upsert
    suspend fun upsertAll(states: List<RatingBackfillStateEntity>)

    @Query("SELECT COUNT(*) FROM rating_backfill_state")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM rating_backfill_state WHERE failureCount > 0")
    suspend fun countFailing(): Int

    @Query("SELECT MIN(nextAttemptAt) FROM rating_backfill_state WHERE failureCount > 0")
    suspend fun earliestRetryAt(): Long?

    /** 手动重试：没查成的作品不再等，下次运行就查。 */
    @Query("UPDATE rating_backfill_state SET nextAttemptAt = :now WHERE failureCount > 0")
    suspend fun retryFailedNow(now: Long)

    /**
     * 挑需要补评分的动画（在数据库里筛选、排序、限量，不整表读进内存）：
     * 有效评分（未缺失、分数大于 0）不超过一个平台；没有记录或已经到了下次可以再试的时间；
     * 其它平台的评分 [checkedSince] 之后没查过。收藏 / 待补池里的作品优先，其余按评分人数从多到少。
     */
    @Query(
        """
        SELECT w.id FROM works w
        LEFT JOIN rating_backfill_state s ON s.workId = w.id
        WHERE w.mediaType = 'ANIME'
          AND (s.workId IS NULL OR s.nextAttemptAt <= :now)
          AND (SELECT COUNT(*) FROM ratings r WHERE r.workId = w.id AND r.missing = 0 AND r.score > 0) <= 1
          AND NOT EXISTS (
            SELECT 1 FROM ratings r
            WHERE r.workId = w.id AND r.sourceId != 'BANGUMI' AND r.fetchedAt > :checkedSince
          )
        ORDER BY
          (EXISTS (SELECT 1 FROM user_collections c WHERE c.localWorkId = w.id)
            OR EXISTS (SELECT 1 FROM backlog_items b WHERE b.workId = w.id)) DESC,
          COALESCE((SELECT MAX(r.voteCount) FROM ratings r WHERE r.workId = w.id), 0) DESC
        LIMIT :limit
        """,
    )
    suspend fun selectTargets(now: Long, checkedSince: Long, limit: Int): List<String>
}
