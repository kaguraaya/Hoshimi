package com.acgcompass.domain.matching

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * 公共缓存补充一次会把几百条候选一起聚类。每部作品的归一化标题只算一次，600 条应在百毫秒级完成；
 * 这里给足余量（2 秒），防止退回到每次比较都重新归一化标题的写法。
 */
class ClusterMatchesScaleTest : StringSpec({
    "six hundred candidates cluster quickly and group each title across sources" {
        val words = listOf("星", "海", "夜", "空", "少女", "物语", "魔法", "学园", "战记", "之歌", "旅", "梦")
        val random = kotlin.random.Random(1)
        val titles = (0 until CANDIDATES / SOURCES).map { index ->
            (0 until WORDS_PER_TITLE).joinToString("") { words[random.nextInt(words.size)] } + " $index"
        }
        val matches = (0 until CANDIDATES).map { index ->
            val source = SourceId.entries[index % SOURCES]
            val title = titles[index % titles.size]
            WorkMatch(
                work = Work(
                    id = "${source.name}:$index",
                    titles = Titles(canonical = title, ja = title),
                    mediaType = MediaType.ANIME,
                    primarySource = source,
                    year = BASE_YEAR + (index % titles.size) % YEAR_SPREAD,
                ),
                matchConfidence = 0.9f,
                sourceTag = source,
                popularity = index,
            )
        }
        clusterMatches(matches)

        val start = System.nanoTime()
        val clusters = clusterMatches(matches)
        val elapsedMillis = ((System.nanoTime() - start) / NANOS_PER_MILLI).toInt()

        clusters.size shouldBe titles.size
        clusters.all { cluster -> cluster.map { it.sourceTag }.toSet().size == cluster.size } shouldBe true
        elapsedMillis shouldBeLessThan TIME_BUDGET_MILLIS
    }
}) {
    private companion object {
        const val CANDIDATES = 600
        const val SOURCES = 3
        const val WORDS_PER_TITLE = 3
        const val BASE_YEAR = 2000
        const val YEAR_SPREAD = 20
        const val NANOS_PER_MILLI = 1_000_000L
        const val TIME_BUDGET_MILLIS = 2_000
    }
}
