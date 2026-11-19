package com.acgcompass.feature.tasteranking

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.acgcompass.core.designsystem.AcgCompassTheme
import com.acgcompass.domain.taste.TasteRankItem
import com.acgcompass.domain.taste.TasteRanking
import com.acgcompass.domain.taste.WorkFormat
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 口味排行页截图，只出图供人工检查。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-420dpi")
class TasteRankingScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rankingList() {
        val items = (1..8).map { i ->
            TasteRankItem(
                subjectId = "$i",
                title = if (i == 2) "一个名字很长很长的作品，会换到第二行显示然后省略" else "示例作品 $i",
                coverUrl = null,
                year = 2010 + i,
                format = if (i % 3 == 0) WorkFormat.MOVIE else WorkFormat.TV,
                finished = i != 4,
                score = 95 - i * 3,
                predictedRating = 8.8 - i * 0.2,
                reason = if (i % 2 == 0) "命中「百合」" else null,
                ownership = if (i == 3) com.acgcompass.domain.taste.RankOwnership.RATED else null,
                myRating = if (i == 3) 9 else null,
            )
        }
        val state =
            TasteRankingUiState(
                loading = false,
                ranking = TasteRanking(items, 2010, 0, unratedCount = 139),
                filter = TasteRankingFilter(hideOwned = false),
                pool = com.acgcompass.domain.taste.LocalPoolStatus(rankable = 2010, missingFeatures = 312),
                expansion = com.acgcompass.domain.taste.PoolExpansion(1001, 300, 1000, running = true),
            )
        compose.setContent {
            AcgCompassTheme(darkTheme = false, dynamicColor = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    TasteRankingContent(state, TasteRankingActions(), PaddingValues(16.dp), 0.dp)
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/taste-ranking.png")
    }
}
