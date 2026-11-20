package com.acgcompass.data.sync

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** 后台任务一批的结果怎么记：没查成 / 没补上要算失败，没东西可做是正常。 */
class BackgroundBatchResultTest : StringSpec({
    "rating backfill: all failed and partly failed both read as failures with counts" {
        val all = RatingBackfillBatch(total = 20, checked = 0, failed = 20)
        all.failureReason() shouldBe "这批 20 部都没查成，多半是网络不通或被限速，会在几小时后自动再试"
        val part = RatingBackfillBatch(total = 20, checked = 15, failed = 5)
        part.summary() shouldBe "挑了 20 部，查完 15 部，5 部没查成"
        RatingBackfillBatch(0, 0, 0).summary() shouldBe "没有需要补的作品"
    }

    "a batch cut short by the time budget says how many are left for next time" {
        val cut = RatingBackfillBatch(total = 20, checked = 12, failed = 0, deferred = 8)
        cut.summary() shouldBe "查完 12 部；到了单次运行时限，剩下 8 部下次继续"
    }

    "the summary names the platforms that failed and the items that timed out" {
        val batch = RatingBackfillBatch(
            total = 20,
            checked = 15,
            failed = 5,
            timedOut = 2,
            failedSources = mapOf("TENRAI" to 1, "ANILIST" to 3),
        )
        batch.summary() shouldBe "挑了 20 部，查完 15 部，5 部没查成；没拿到结果的平台：AniList 3 部、Tenrai 1 部；2 部等额度超时"
    }

    "stop reasons from WorkManager read as plain explanations" {
        describeStopReason(3) shouldBe "超过了系统给后台任务的运行时限"
        describeStopReason(7) shouldBe "网络断开或切换"
        describeStopReason(-512) shouldBe "系统没有给出原因"
    }

    "feature enrichment is stalled only when there was work and nothing got filled" {
        EnrichmentBatch(attempted = 20, progressed = false, remaining = true).stalled shouldBe true
        EnrichmentBatch(attempted = 0, progressed = false, remaining = false).stalled shouldBe false
        EnrichmentBatch(attempted = 0, progressed = false, remaining = false).summary() shouldBe "没有要补的作品"
        EnrichmentBatch(attempted = 20, progressed = true, remaining = true).stalled shouldBe false
    }

    "outbox records every run, including when there is nothing to push" {
        outboxSummary(canPush = true, pushed = 0) shouldBe "没有要推送的修改"
        outboxSummary(canPush = true, pushed = 2) shouldBe "推送了 2 条"
        outboxSummary(canPush = false, pushed = 0) shouldBe "没登录 Bangumi（或没确认非官方地址），不推送"
    }
})
