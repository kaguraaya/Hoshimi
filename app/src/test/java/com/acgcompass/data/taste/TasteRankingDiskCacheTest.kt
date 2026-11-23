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
        assertNull(cache.read("gate2"))
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

        cache.write(ranking, "gate2")

        assertEquals(ranking, cache.read("gate2"))
        // 模型版本不同时不返回旧结果。
        assertNull(cache.read("legacy1"))
        // 没记画像输入指纹的旧文件不能当最新结果直接用。
        assertNull(cache.readExact("gate2", "k1", 1200))
    }

    @Test
    fun withoutBacktestStoredValuesAreKept() {
        val ranking = TasteRanking(
            items = listOf(
                TasteRankItem("1", "评过", null, 2020, WorkFormat.TV, true, 77, 8.0, null, RankOwnership.RATED, 8),
                TasteRankItem("2", "没评", null, 2021, WorkFormat.TV, true, 90, 8.6, null, null),
            ),
            poolSize = 2,
            computedAt = 1L,
        )
        // 冷启动时回测还没算完：画像输入没变，存下的回测值就是会算出的值，原样保留。
        assertEquals(ranking, ranking.withBacktest(null, com.acgcompass.domain.taste.TasteMatchBranch.STANDARD))
    }

    @Test
    fun exactReadNeedsSameInputsKeyAndPoolSize() {
        val ranking = TasteRanking(
            items = listOf(TasteRankItem("1", "作品", null, 2024, WorkFormat.TV, true, 88, 8.1, null, null)),
            poolSize = 900,
            computedAt = 7L,
        )
        cache.write(ranking, "gate3", inputsKey = "abc", featureCount = 2260)

        assertEquals(ranking, cache.readExact("gate3", "abc", 2260))
        assertNull(cache.readExact("gate3", "abd", 2260))
        assertNull(cache.readExact("gate3", "abc", 2261))
        assertNull(cache.readExact("legacy1", "abc", 2260))
    }
}
