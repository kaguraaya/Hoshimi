package com.acgcompass.feature.home

import com.acgcompass.data.credential.CredentialStatus
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 *
 * 覆盖两个纯函数：
 * - [buildDailyDraw]：**确定性**——同一日期 + 待补规模必产同一宜 / 忌；不同种子可不同。
 * - [buildSyncReminder]：仅纳入已配置可同步源、失败标记、时间文案、空时返回 `null`。
 */
class HomeDailyDrawTest : StringSpec({

    // --- 今日补番签：确定性 ---

    "同一日期与待补规模产出完全相同的宜 / 忌（确定性）" {
        val date = LocalDate.of(2024, 5, 20)
        val a = buildDailyDraw(date = date, backlogSize = 12)
        val b = buildDailyDraw(date = date, backlogSize = 12)
        a shouldBe b
    }

    "同一日期、不同待补规模可派生不同结果（种子参与）" {
        val date = LocalDate.of(2024, 5, 20)
        val results = (0..30).map { buildDailyDraw(date = date, backlogSize = it) }
        // 至少存在两种不同的宜文案，证明 backlogSize 真正参与种子。
        (results.map { it.shouldText }.distinct().size > 1) shouldBe true
    }

    "结果文案恒来自候选池且非空（绝不伪造空签）" {
        val draw = buildDailyDraw(date = LocalDate.of(2024, 1, 1), backlogSize = 5)
        draw.shouldText.isNotBlank() shouldBe true
        draw.shouldNotText.isNotBlank() shouldBe true
    }

    "多次跨多天调用均稳定（无随机性 / 无副作用）" {
        val base = LocalDate.of(2024, 6, 1)
        (0 until 14).forEach { offset ->
            val date = base.plusDays(offset.toLong())
            buildDailyDraw(date = date, backlogSize = 3) shouldBe
                buildDailyDraw(date = date, backlogSize = 3)
        }
    }

    // --- 同步提醒 ---

    "无任何已配置可同步源时返回 null（界面不展示该区）" {
        val statuses = mapOf(
            CredentialSourceId.BANGUMI to CredentialStatus(configured = false),
        )
        buildSyncReminder(statuses, nowMillis = 0L).shouldBeNull()
    }

    "仅纳入已配置可同步源，AI_PROVIDER / JIKAN 不计入" {
        val now = 60_000L
        val statuses = mapOf(
            CredentialSourceId.BANGUMI to CredentialStatus(configured = true, lastTestedAt = now),
            CredentialSourceId.ANILIST to CredentialStatus(configured = false),
            CredentialSourceId.AI_PROVIDER to CredentialStatus(configured = true, lastTestedAt = now),
        )
        val reminder = buildSyncReminder(statuses, nowMillis = now)
        reminder.shouldNotBeNull()
        reminder.lines.map { it.sourceLabel } shouldContainExactly listOf("Bangumi")
    }

    "任一源测试失败时置 hasFailure 并标记该行" {
        val now = 120_000L
        val statuses = mapOf(
            CredentialSourceId.BANGUMI to CredentialStatus(
                configured = true,
                lastTestedAt = now - 60_000L,
                status = CredentialStatus.Status.TEST_FAILED,
            ),
            CredentialSourceId.VNDB to CredentialStatus(
                configured = true,
                lastTestedAt = now,
                status = CredentialStatus.Status.TEST_SUCCESS,
            ),
        )
        val reminder = buildSyncReminder(statuses, nowMillis = now)
        reminder.shouldNotBeNull()
        reminder.hasFailure shouldBe true
        reminder.lines.first { it.sourceLabel == "Bangumi" }.failed shouldBe true
        reminder.lines.first { it.sourceLabel == "VNDB" }.failed shouldBe false
    }

    "从未测试的源显示「尚未同步」（缺失即标记，绝不伪造）" {
        val statuses = mapOf(
            CredentialSourceId.ANILIST to CredentialStatus(configured = true, lastTestedAt = null),
        )
        val reminder = buildSyncReminder(statuses, nowMillis = 1_000_000L)
        reminder.shouldNotBeNull()
        reminder.lines.single().statusText shouldBe "尚未同步"
    }
})
