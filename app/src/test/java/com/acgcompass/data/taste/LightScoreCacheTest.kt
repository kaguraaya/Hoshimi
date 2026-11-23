package com.acgcompass.data.taste

import androidx.test.core.app.ApplicationProvider
import com.acgcompass.domain.taste.LightScore
import com.acgcompass.domain.taste.WorkFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 轻量分缓存：同一个键下能跨实例（相当于重启应用）读回；键变了或作品资料更新过就不命中。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LightScoreCacheTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun feature(id: String, updatedAt: Long) = WorkFeature(subjectId = id, updatedAt = updatedAt)

    @Test
    fun scoresSurviveRestartUnderSameKey() {
        val a = feature("1", 10L)
        val b = feature("2", 20L)
        val first = LightScoreCache(context)
        assertTrue(first.lookup("k1", mapOf("1" to a, "2" to b)).isEmpty())
        first.store(
            "k1",
            mapOf("1" to (a to LightScore(80, 75, 0.6, 0.3)), "2" to (b to LightScore(55, 50, 0.2, null))),
        )

        val restarted = LightScoreCache(context)
        assertEquals(
            mapOf("1" to LightScore(80, 75, 0.6, 0.3), "2" to LightScore(55, 50, 0.2, null)),
            restarted.lookup("k1", mapOf("1" to a, "2" to b)),
        )
        // 作品资料更新过：只有这一部不命中。
        assertEquals(setOf("2"), restarted.lookup("k1", mapOf("1" to feature("1", 11L), "2" to b)).keys)
        // 画像输入变了：整张表作废。
        assertTrue(LightScoreCache(context).lookup("k2", mapOf("1" to a, "2" to b)).isEmpty())
    }
}
