package com.acgcompass.feature.tasks

import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.model.BackgroundTaskStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.time.ZoneId
import java.time.ZonedDateTime

class BackgroundTaskTextTest : StringSpec({
    val zone = ZoneId.of("Asia/Shanghai")
    val now = ZonedDateTime.of(2026, 11, 19, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
    val minute = 60_000L
    val hour = 60 * minute

    "relative time reads naturally" {
        relativeTime(now - 20_000L, now, zone) shouldBe "刚刚"
        relativeTime(now + 20_000L, now, zone) shouldBe "1 分钟内"
        relativeTime(now - 12 * minute, now, zone) shouldBe "12 分钟前"
        relativeTime(now + 3 * hour, now, zone) shouldBe "3 小时后"
        relativeTime(now - 8 * hour, now, zone) shouldBe "今天 12:00"
        relativeTime(now - 22 * hour, now, zone) shouldBe "昨天 22:00"
        relativeTime(now + 10 * hour, now, zone) shouldBe "明天 06:00"
        relativeTime(now - 3 * 24 * hour, now, zone) shouldBe "11月16日 20:00"
    }

    "a failure after the last success is shown with its reason" {
        val status = BackgroundTaskStatus(
            kind = BackgroundTaskKind.RATING_BACKFILL,
            lastSuccessAt = now - 2 * hour,
            lastFailureAt = now - 5 * minute,
            lastError = "请求太频繁被限速，稍后会自动再试",
            nextRunAt = now + hour,
        )
        status.badge() shouldBe TaskBadge.FAILED
        val lines = status.detailLines(now, zone)
        lines shouldContain "上次失败：5 分钟前，请求太频繁被限速，稍后会自动再试"
        lines shouldContain "下次：1 小时后"
    }

    "an older failure is hidden once a later run succeeded" {
        val status = BackgroundTaskStatus(
            kind = BackgroundTaskKind.SYNC,
            lastSuccessAt = now - minute * 2,
            lastFailureAt = now - hour,
            lastError = "网络连不上或超时",
        )
        status.badge() shouldBe TaskBadge.OK
        status.detailLines(now, zone).none { it.startsWith("上次失败") } shouldBe true
    }

    "disabled tasks say so instead of a next run time" {
        val status = BackgroundTaskStatus(kind = BackgroundTaskKind.CACHE_FILL, disabled = true, nextRunAt = now + hour)
        status.badge() shouldBe TaskBadge.DISABLED
        status.detailLines(now, zone) shouldBe listOf("设置里已关闭，不会按计划运行")
    }

    "a retry due within a minute or already overdue reads as waiting for conditions, never 刚刚" {
        listOf(now - 5 * minute, now, now + 30_000L).forEach { next ->
            val status = BackgroundTaskStatus(kind = BackgroundTaskKind.SYNC, lastError = "网络连接失败", nextRunAt = next)
            status.detailLines(now, zone) shouldContain "下次：条件满足后马上运行"
        }
    }

    "errorCurrent overrides the time comparison (sync / backup clear their error on success)" {
        val status = BackgroundTaskStatus(
            kind = BackgroundTaskKind.AUTO_BACKUP,
            lastSuccessAt = now - hour,
            lastError = "空间不足",
            errorCurrent = true,
        )
        status.badge() shouldBe TaskBadge.FAILED
        status.detailLines(now, zone) shouldContain "上次失败：空间不足"
    }
})
