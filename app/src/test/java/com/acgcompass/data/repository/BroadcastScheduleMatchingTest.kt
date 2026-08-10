package com.acgcompass.data.repository

import com.acgcompass.domain.schedule.BroadcastScheduleEntry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek

class BroadcastScheduleMatchingTest : StringSpec({
    "Bangumi native title matches an AniList native title when display titles differ" {
        val bangumi = schedule(
            title = "Sousou no Frieren",
            titleCn = "Frieren",
            variants = listOf("Sousou no Frieren", "Frieren"),
        )
        val aniList = schedule(
            title = "Frieren: Beyond Journey's End",
            variants = listOf("Sousou no Frieren", "Frieren: Beyond Journey's End"),
        )

        broadcastScheduleTitlesMatch(bangumi, aniList) shouldBe true
    }

    "unrelated titles are not merged into the Bangumi calendar" {
        broadcastScheduleTitlesMatch(
            schedule("Steins;Gate", variants = listOf("STEINS;GATE")),
            schedule("Steins;Gate 0", variants = listOf("Steins Gate Zero")),
        ) shouldBe false
    }

    "Tenrai is consulted only when Bangumi time is unknown and AniList has no match" {
        val unknown = schedule("Example", variants = listOf("Example"), timeToBeDetermined = true)
        val precise = schedule("Precise", timeToBeDetermined = false)

        needsTenraiScheduleSupplement(listOf(unknown), emptyList()) shouldBe true
        needsTenraiScheduleSupplement(listOf(unknown), listOf(schedule("Example"))) shouldBe false
        needsTenraiScheduleSupplement(listOf(precise), emptyList()) shouldBe false
    }
})

private fun schedule(
    title: String,
    titleCn: String? = null,
    variants: List<String> = emptyList(),
    timeToBeDetermined: Boolean = false,
): BroadcastScheduleEntry = BroadcastScheduleEntry(
    scheduleId = title,
    workId = title,
    sourceItemId = title,
    title = title,
    titleCn = titleCn,
    titleVariants = variants,
    dayOfWeek = DayOfWeek.MONDAY,
    timeToBeDetermined = timeToBeDetermined,
)
