package com.acgcompass.data.taste

import androidx.test.core.app.ApplicationProvider
import com.acgcompass.domain.taste.RankOwnership
import com.acgcompass.domain.taste.TasteRankItem
import com.acgcompass.domain.taste.TasteRanking
import com.acgcompass.domain.taste.WorkFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TasteRankingDiskCacheTest {
    private val cache = TasteRankingDiskCache(ApplicationProvider.getApplicationContext())

    @Test
    fun writtenRankingReadsBackUnchanged() {
        assertNull(cache.read())
        val ranking = TasteRanking(
            items = listOf(
                TasteRankItem(
                    "1", "作品", "https://example.com/a.jpg", 2024, WorkFormat.TV, true, 88, 8.1, "命中「日常」", null,
                ),
                TasteRankItem("2", "另一部", null, null, WorkFormat.MOVIE, null, 70, null, null, RankOwnership.RATED, 9),
            ),
            poolSize = 1200,
            computedAt = 42L,
            unratedCount = 1100,
        )

        cache.write(ranking)

        assertEquals(ranking, cache.read())
    }
}
