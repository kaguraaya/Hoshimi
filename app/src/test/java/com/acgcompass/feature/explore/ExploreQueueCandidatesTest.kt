package com.acgcompass.feature.explore

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly

class ExploreQueueCandidatesTest : StringSpec({
    "default queue excludes Jikan and Tenrai and prefers Bangumi within a title cluster" {
        val candidates = selectExploreCandidates(
            all = listOf(
                work("jikan:1", "Same Work", SourceId.JIKAN),
                work("bangumi:1", "Same Work", SourceId.BANGUMI),
                work("jikan:2", "Jikan Only", SourceId.JIKAN),
                work("tenrai:2", "Tenrai Only", SourceId.TENRAI),
                work("anilist:3", "Another Work", SourceId.ANILIST),
            ),
            excluded = emptySet(),
        )

        candidates.map(Work::id) shouldContainExactly listOf("bangumi:1", "anilist:3")
    }

    "excluded works and non-anime media do not enter the queue" {
        val candidates = selectExploreCandidates(
            all = listOf(
                work("bangumi:1", "Anime", SourceId.BANGUMI),
                work("vndb:1", "Game", SourceId.VNDB, MediaType.VN),
            ),
            excluded = setOf("bangumi:1"),
        )

        candidates shouldContainExactly emptyList()
    }

    "background matches do not move the card currently being viewed" {
        val current = card("current", 0.50f)
        val next = card("next", 0.60f)
        val better = card("better", 0.95f)

        insertExploreCardPreservingProgress(listOf(current, next), 0, better)
            .map { it.workId } shouldContainExactly listOf("current", "better", "next")
    }
})

private fun card(id: String, fraction: Float) = ExploreCardUiModel(
    workId = id,
    coverUrl = null,
    title = id,
    meta = "",
    tastePercent = "${(fraction * 100).toInt()}%",
    tasteFraction = fraction,
    tasteQualitative = "",
    reason = "",
    tags = emptyList(),
    synopsis = null,
)

private fun work(id: String, title: String, source: SourceId, mediaType: MediaType = MediaType.ANIME): Work = Work(
    id = id,
    titles = Titles(canonical = title, cn = title),
    mediaType = mediaType,
    primarySource = source,
)
