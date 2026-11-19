package com.acgcompass.feature.discover

import com.acgcompass.core.designsystem.WorkCardUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

class RankingRefreshMessageTest {
    private fun ranked(id: String, rating: String) =
        RankedWork(id, WorkCardUiModel(coverUrl = null, title = id, subtitle = "", type = "TV", ratingText = rating))

    @Test
    fun reportsChangesOrUpToDate() {
        val before = listOf(ranked("1", "8.0"), ranked("2", "7.5"))
        assertEquals("榜单已是最新，名次和评分都没有变化", rankingRefreshMessage(true, before, before))
        val swapped = listOf(ranked("2", "7.5"), ranked("1", "8.0"))
        assertEquals("榜单已更新：2 部作品的名次或评分有变化", rankingRefreshMessage(true, before, swapped))
        assertEquals("榜单这次没取到，显示的是上次的结果", rankingRefreshMessage(false, before, before))
    }
}
