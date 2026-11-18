package com.acgcompass.feature.home

import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiDailyDrawInputTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    @Suppress("LongParameterList")
    private fun entry(
        id: String,
        cn: String?,
        canonical: String,
        episodes: Int?,
        addedDaysAgo: Long,
        withWork: Boolean = true,
    ) = HomeBacklogEntry(
        item = BacklogItem(workId = id, addedAt = now - addedDaysAgo * day),
        work = if (withWork) {
            Work(
                id = id,
                titles = Titles(canonical = canonical, cn = cn),
                mediaType = MediaType.ANIME,
                units = Units(episodes = episodes),
                primarySource = SourceId.BANGUMI,
            )
        } else {
            null
        },
    )

    @Test
    fun listsOldestEntriesWithTitlesEpisodesAndDays() {
        val input = buildAiDailyDrawInput(
            LocalDate.of(2026, 11, 21),
            listOf(
                entry("1", "孤独摇滚！", "ぼっち・ざ・ろっく！", 12, addedDaysAgo = 3),
                entry("2", null, "Frieren", 28, addedDaysAgo = 40),
                entry("3", null, "unknown", null, addedDaysAgo = 1, withWork = false),
            ),
            now,
        )
        assertTrue(input.contains("星期六，周末"))
        assertTrue(input.contains("待补池共 3 部，以下是其中 2 部"))
        assertTrue(input.indexOf("Frieren") < input.indexOf("孤独摇滚！"))
        assertTrue(input.contains("- 孤独摇滚！，12 集，加入 3 天"))
        assertFalse(input.contains("unknown"))
    }

    @Test
    fun fingerprintChangesByDate() {
        assertEquals("daily-draw:2026-11-18", aiDailyDrawFingerprint(LocalDate.of(2026, 11, 18)))
    }
}
