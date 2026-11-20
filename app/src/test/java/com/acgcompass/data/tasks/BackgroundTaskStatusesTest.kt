package com.acgcompass.data.tasks

import com.acgcompass.data.local.entity.TaskRunEntity
import com.acgcompass.domain.model.BackgroundTaskKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class BackgroundTaskStatusesTest : StringSpec({
    val allOn = TaskSwitches(sync = true, cacheFill = true, backfill = true, backup = true)

    @Suppress("LongParameterList") // 测试夹具，各输入都有默认值
    fun inputs(
        runs: List<TaskRunEntity> = emptyList(),
        switches: TaskSwitches = allOn,
        schedules: Map<BackgroundTaskKind, WorkSchedule> = emptyMap(),
        syncSuccess: Long? = null,
        syncError: String? = null,
        backupSuccess: Long? = null,
    ) = TaskInputs(
        runs = runs.associateBy { it.taskKey },
        syncLastSuccessAt = syncSuccess,
        syncLastError = syncError,
        backupLastSuccessAt = backupSuccess,
        backupLastError = null,
        switches = switches,
        schedules = schedules,
    )

    fun run(kind: BackgroundTaskKind, running: Boolean = false, success: Long? = null, failure: Long? = null) =
        TaskRunEntity(kind.key, running, null, success, failure, failure?.let { "网络连不上或超时" }, null, 0L)

    "every kind gets a row in declaration order" {
        buildBackgroundTaskStatuses(inputs(), 0, 0, null).map { it.kind } shouldBe BackgroundTaskKind.entries
    }

    "running comes from either the record or WorkManager" {
        val statuses = buildBackgroundTaskStatuses(
            inputs(
                runs = listOf(run(BackgroundTaskKind.SYNC, running = true)),
                schedules = mapOf(BackgroundTaskKind.CACHE_FILL to WorkSchedule(running = true, nextRunAt = null)),
            ),
            0,
            0,
            null,
        ).associateBy { it.kind }
        statuses.getValue(BackgroundTaskKind.SYNC).running shouldBe true
        statuses.getValue(BackgroundTaskKind.CACHE_FILL).running shouldBe true
        statuses.getValue(BackgroundTaskKind.OUTBOX).running shouldBe false
    }

    "sync and backup keep the success time recorded before task records existed" {
        val statuses = buildBackgroundTaskStatuses(
            inputs(syncSuccess = 500L, backupSuccess = 700L),
            0,
            0,
            null,
        ).associateBy { it.kind }
        statuses.getValue(BackgroundTaskKind.SYNC).lastSuccessAt shouldBe 500L
        statuses.getValue(BackgroundTaskKind.AUTO_BACKUP).lastSuccessAt shouldBe 700L
    }

    "backfill shows waiting retries and the earliest retry time; switched off means no manual retry" {
        val on = buildBackgroundTaskStatuses(
            inputs(schedules = mapOf(BackgroundTaskKind.RATING_BACKFILL to WorkSchedule(false, 9_000L))),
            outboxPending = 3,
            backfillFailing = 4,
            backfillRetryAt = 5_000L,
        ).associateBy { it.kind }
        on.getValue(BackgroundTaskKind.RATING_BACKFILL).pendingNote shouldBe "4 部上次没查成，会自动再试"
        on.getValue(BackgroundTaskKind.RATING_BACKFILL).nextRunAt shouldBe 5_000L
        on.getValue(BackgroundTaskKind.OUTBOX).pendingNote shouldBe "还有 3 条修改没推到 Bangumi"

        val off = buildBackgroundTaskStatuses(
            inputs(switches = allOn.copy(backfill = false)),
            0,
            0,
            null,
        ).first { it.kind == BackgroundTaskKind.RATING_BACKFILL }
        off.disabled shouldBe true
        off.retryable shouldBe false
    }

    "cache fill shows exactly the per-source records of the cache page and offers no manual run" {
        val runs = listOf(
            com.acgcompass.domain.cache.CacheFillRun(
                "BANGUMI",
                3,
                false,
                2_000L,
                "TARGET_REACHED",
                null,
                lastSuccessAt = 2_000L,
            ),
            com.acgcompass.domain.cache.CacheFillRun("ANILIST", 1, false, 3_000L, "FAILED", "请求过于频繁"),
        )
        val cache = buildBackgroundTaskStatuses(inputs().copy(cacheRuns = runs), 0, 0, null)
            .first { it.kind == BackgroundTaskKind.CACHE_FILL }
        cache.cacheRuns shouldBe runs
        cache.lastSuccessAt shouldBe 2_000L
        cache.lastFailureAt shouldBe 3_000L
        cache.lastError shouldBe "请求过于频繁"
        cache.errorCurrent shouldBe true
        cache.retryable shouldBe false
    }

    "sync and backup errors follow the original pages: cleared on success, shown while present" {
        val ok = buildBackgroundTaskStatuses(
            inputs(runs = listOf(run(BackgroundTaskKind.SYNC, success = 100L, failure = 200L)), syncSuccess = 300L),
            0,
            0,
            null,
        ).first { it.kind == BackgroundTaskKind.SYNC }
        ok.errorCurrent shouldBe false
        val failing = buildBackgroundTaskStatuses(inputs(syncSuccess = 300L, syncError = "网络连接失败"), 0, 0, null)
            .first { it.kind == BackgroundTaskKind.SYNC }
        failing.errorCurrent shouldBe true
        failing.lastError shouldBe "网络连接失败"
    }

    "pool expansion and detail extras cannot be retried from the page" {
        val statuses = buildBackgroundTaskStatuses(inputs(), 0, 0, null).associateBy { it.kind }
        statuses.getValue(BackgroundTaskKind.POOL_EXPAND).retryable shouldBe false
        statuses.getValue(BackgroundTaskKind.DETAIL_EXTRAS).retryable shouldBe false
    }
})
