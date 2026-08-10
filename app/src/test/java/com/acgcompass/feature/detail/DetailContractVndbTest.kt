package com.acgcompass.feature.detail

import com.acgcompass.domain.model.CollectionState
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.visualnovel.VisualNovelDetail
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DetailContractVndbTest : StringSpec({
    "an unmatched VNDB work keeps local records editable without claiming cloud sync" {
        val state = buildDetailUiState(
            work = vndbWork(),
            ratings = RatingAggregate(
                perSource = mapOf(SourceId.VNDB to RatingEntry(score = 82f, voteCount = 1_000)),
            ),
            collectionState = CollectionState(workId = "v1", status = "想玩", rating = 9),
        )

        state.isVndb shouldBe true
        state.personal.recordEditable shouldBe true
        state.personal.inLibrary shouldBe true
        state.vndbBangumiLinked shouldBe false
    }

    "a high confidence Bangumi link is exposed separately from VNDB ratings" {
        val state = buildDetailUiState(
            work = vndbWork(),
            ratings = RatingAggregate(
                perSource = mapOf(
                    SourceId.VNDB to RatingEntry(score = 82f, voteCount = 1_000),
                    SourceId.BANGUMI to RatingEntry(score = 7.5f, voteCount = 500),
                ),
            ),
            extras = DetailExtras(
                vndbDetail = VisualNovelDetail(id = "v1", title = "Example", bangumiSubjectId = 42),
            ),
        )

        state.vndbBangumiLinked shouldBe true
        state.ratings.first { it.platform == "VNDB" }.scoreText shouldBe "82/100"
        state.ratings.first { it.platform == "Bangumi" }.scoreText shouldBe "7.5/10"
    }
})

private fun vndbWork(): Work = Work(
    id = "v1",
    titles = Titles(canonical = "Example"),
    mediaType = MediaType.VN,
    primarySource = SourceId.VNDB,
)
