package com.acgcompass.domain.visualnovel

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class VisualNovelCachePolicyTest : StringSpec({
    "ranking cache becomes stale at the 24 hour boundary" {
        val cachedAt = 1_000L
        VisualNovelCachePolicy.rankingIsStale(
            cachedAt,
            cachedAt + VisualNovelCachePolicy.RANKING_TTL_MILLIS - 1,
        ) shouldBe false
        VisualNovelCachePolicy.rankingIsStale(
            cachedAt,
            cachedAt + VisualNovelCachePolicy.RANKING_TTL_MILLIS,
        ) shouldBe true
    }

    "a future cache timestamp is not treated as stale" {
        VisualNovelCachePolicy.rankingIsStale(cachedAt = 2_000L, now = 1_000L) shouldBe false
    }

    "ranking hash is stable but changes for order rating votes adult flag or pagination" {
        val first = item("v1", rating = 8.0f, votes = 100, adult = false)
        val second = item("v2", rating = 7.0f, votes = 50, adult = false)
        val base = VisualNovelPage(items = listOf(first, second), page = 1, hasMore = true)
        val hash = VisualNovelCachePolicy.pageContentHash(base)

        VisualNovelCachePolicy.pageContentHash(base.copy(page = 2)) shouldBe hash
        VisualNovelCachePolicy.pageContentHash(base.copy(items = listOf(second, first))) shouldNotBe hash
        VisualNovelCachePolicy.pageContentHash(
            base.copy(
                items = listOf(
                    first.copy(work = first.work.copy(titles = Titles(canonical = "Updated title"))),
                    second,
                ),
            ),
        ) shouldNotBe hash
        VisualNovelCachePolicy.pageContentHash(
            base.copy(items = listOf(first.copy(rating = 8.1f), second)),
        ) shouldNotBe hash
        VisualNovelCachePolicy.pageContentHash(
            base.copy(items = listOf(first.copy(voteCount = 101), second)),
        ) shouldNotBe hash
        VisualNovelCachePolicy.pageContentHash(
            base.copy(items = listOf(first.copy(adult = true), second)),
        ) shouldNotBe hash
        VisualNovelCachePolicy.pageContentHash(base.copy(hasMore = false)) shouldNotBe hash
    }
})

private fun item(id: String, rating: Float, votes: Int, adult: Boolean): VisualNovelListItem = VisualNovelListItem(
    work = Work(
        id = id,
        titles = Titles(canonical = id),
        mediaType = MediaType.VN,
        primarySource = SourceId.VNDB,
    ),
    rating = rating,
    voteCount = votes,
    adult = adult,
)
