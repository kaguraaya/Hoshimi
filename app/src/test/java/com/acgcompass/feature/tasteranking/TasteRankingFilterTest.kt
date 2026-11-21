package com.acgcompass.feature.tasteranking

import com.acgcompass.domain.taste.RankOwnership
import com.acgcompass.domain.taste.TasteRankItem
import com.acgcompass.domain.taste.TasteRanking
import com.acgcompass.domain.taste.WorkFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class TasteRankingFilterTest {
    private fun item(
        id: String,
        format: WorkFormat = WorkFormat.TV,
        year: Int? = 2020,
        finished: Boolean? = true,
        ownership: RankOwnership? = null,
    ) = TasteRankItem(id, "作品${id}号", null, year, format, finished, 80, 8.0, null, ownership)

    private val items = listOf(
        item("1"),
        item("2", format = WorkFormat.MOVIE, year = 2015),
        item("3", year = 2005, finished = null),
        item("4", ownership = RankOwnership.BACKLOG),
        item("5", year = null, finished = false),
        item("6", ownership = RankOwnership.COLLECTED, format = WorkFormat.OVA, year = 1998),
        item("7", ownership = RankOwnership.RATED),
    )

    private fun visible(filter: TasteRankingFilter) =
        TasteRankingUiState(loading = false, ranking = TasteRanking(items, 100, 0), filter = filter).visible.map {
            it.subjectId
        }

    @Test
    fun hidesOwnedByDefault() {
        assertEquals(listOf("1", "2", "3", "5"), visible(TasteRankingFilter()))
        // 关掉后评过分、已收藏、待补的都回来。
        assertEquals(items.map { it.subjectId }, visible(TasteRankingFilter(hideOwned = false)))
    }

    @Test
    fun formatEraAndFinishedCombine() {
        assertEquals(listOf("2"), visible(TasteRankingFilter(formats = setOf(WorkFormat.MOVIE))))
        assertEquals(listOf("2"), visible(TasteRankingFilter(era = EraFilter.TEENS)))
        assertEquals(listOf("6"), visible(TasteRankingFilter(era = EraFilter.OLDER, hideOwned = false)))
        // 年份未知的作品在选了年代时不出现；完结状态未知的在「只看已完结」时不出现。
        assertEquals(listOf("1"), visible(TasteRankingFilter(era = EraFilter.RECENT, finishedOnly = true)))
        assertEquals(
            listOf("1", "4", "7"),
            visible(TasteRankingFilter(era = EraFilter.RECENT, finishedOnly = true, hideOwned = false)),
        )
    }

    @Test
    fun formatComesFromBangumiPlatform() {
        assertEquals(WorkFormat.TV, WorkFormat.of("TV"))
        assertEquals(WorkFormat.MOVIE, WorkFormat.of("剧场版"))
        assertEquals(WorkFormat.OVA, WorkFormat.of("OVA"))
        assertEquals(WorkFormat.WEB, WorkFormat.of("WEB"))
        assertEquals(WorkFormat.OTHER, WorkFormat.of(null))
        assertEquals(WorkFormat.OTHER, WorkFormat.of("动态漫画"))
    }
}
