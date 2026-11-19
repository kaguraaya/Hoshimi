package com.acgcompass.domain.matching

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 *
 * 验证：紧凑归一精确匹配（9nine/9-nine）、原文一致合并、系列总称防误并、年份/类型冲突不并。
 */
class CrossSourceMergeTest : StringSpec({

    fun work(
        id: String,
        canonical: String,
        ja: String? = null,
        type: MediaType = MediaType.ANIME,
        year: Int? = 2021,
        source: SourceId = SourceId.BANGUMI,
    ): Work = Work(
        id = id,
        titles = Titles(canonical = canonical, ja = ja),
        mediaType = type,
        year = year,
        primarySource = source,
    )

    "不同平台的相同数字 id 不算同一作品，同一平台的相同 id 算" {
        val bangumi = work("21", "进击的巨人", year = 2013, source = SourceId.BANGUMI)
        val anilist = work("21", "One Piece", year = 1999, source = SourceId.ANILIST)
        sameWork(bangumi, anilist) shouldBe false
        sameWork(bangumi, bangumi.copy(titles = Titles(canonical = "别的写法"))) shouldBe true
        val clusters = clusterMatches(
            listOf(
                WorkMatch(work = bangumi, matchConfidence = 0.9f, sourceTag = SourceId.BANGUMI),
                WorkMatch(work = anilist, matchConfidence = 0.8f, sourceTag = SourceId.ANILIST),
            ),
        )
        clusters.size shouldBe 2
    }

    "A≈B、B≈C 但 A 和 C 年份冲突时，C 不会被串进 A 的簇" {
        val a = work("1", "某作品", ja = "サクヒン", year = 2013, source = SourceId.BANGUMI)
        val b = work("2", "Sakuhin", ja = "サクヒン", year = null, source = SourceId.ANILIST)
        val c = work("3", "Sakuhin", ja = "サクヒン", year = 2017, source = SourceId.TENRAI)
        sameWork(a, b) shouldBe true
        sameWork(b, c) shouldBe true
        sameWork(a, c) shouldBe false
        val clusters = clusterMatches(
            listOf(
                WorkMatch(work = a, matchConfidence = 0.9f, sourceTag = SourceId.BANGUMI),
                WorkMatch(work = b, matchConfidence = 0.8f, sourceTag = SourceId.ANILIST),
                WorkMatch(work = c, matchConfidence = 0.7f, sourceTag = SourceId.TENRAI),
            ),
        )
        clusters.map { cluster -> cluster.map { it.work.id }.toSet() } shouldBe listOf(setOf("1", "2"), setOf("3"))
    }

    "靠中间一部搭桥的正常跨平台合并不受影响" {
        val a = work("1", "某作品", ja = "サクヒン", year = 2013, source = SourceId.BANGUMI)
        val b = work("2", "Sakuhin", ja = "サクヒン", year = 2013, source = SourceId.ANILIST)
        val c = work("3", "Sakuhin", year = 2013, source = SourceId.TENRAI)
        val clusters = clusterMatches(
            listOf(
                WorkMatch(work = a, matchConfidence = 0.9f, sourceTag = SourceId.BANGUMI),
                WorkMatch(work = b, matchConfidence = 0.8f, sourceTag = SourceId.ANILIST),
                WorkMatch(work = c, matchConfidence = 0.7f, sourceTag = SourceId.TENRAI),
            ),
        )
        clusters.size shouldBe 1
    }

    "9nine 与 9-nine 仅空格/连字符差异应合并（紧凑归一）" {
        val a = work("1", "9nine", source = SourceId.BANGUMI)
        val b = work("2", "9-nine", source = SourceId.ANILIST)
        sameWork(a, b) shouldBe true
    }

    "原文标题一致且年份/类型不冲突应合并（9-nine- 新章 ↔ 9-nine- Shinshou）" {
        val a = work("3", "9-nine- 新章", ja = "9-nine- そらいろそらうたそらのおと")
        val b = work("4", "9-nine- Shinshou", ja = "9-nine- そらいろそらうたそらのおと")
        sameWork(a, b) shouldBe true
    }

    "类型冲突（动画 vs 视觉小说）即使标题相近也不应自动合并相同年份不同作" {
        // 不同核心标题 + 类型冲突 → 不合并。
        val a = work("5", "完全不同的作品 A", type = MediaType.ANIME)
        val b = work("6", "另一个无关作品 B", type = MediaType.VN)
        sameWork(a, b) shouldBe false
    }

    "系列总称（过短前缀）不应与具体分作误并" {
        // 「9 nine」过短前缀，核心包含判定要求长度≥10，避免误并。
        val umbrella = work("7", "9 nine")
        val entry = work("8", "9-nine-九次九日九重色", ja = "9-nine-九次九日九重色")
        sameWork(umbrella, entry) shouldBe false
    }

    "来源省略中段副标题但保留系列和篇章名时应合并" {
        val concise = work("9", "本好きの下剋上 領主の養女", year = 2026, source = SourceId.ANILIST)
        val complete = work(
            "10",
            "本好きの下剋上～司書になるためには手段を選んでいられません～領主の養女",
            year = 2026,
        )
        sameWork(concise, complete) shouldBe true
    }

    "同系列正片与外传不应因前后标题相近而合并" {
        val main = work("11", "星空物语 最终章")
        val sideStory = work("12", "星空物语 外传 最终章")
        sameWork(main, sideStory) shouldBe false
    }

    "同系列正片与剧场版不应自动合并" {
        val series = work("13", "银河旅程")
        val movie = work("14", "银河旅程 剧场版")
        sameWork(series, movie) shouldBe false
    }

    // --- 代表条目综合得分 --------------------------------------------------

    fun match(id: String, confidence: Float, popularity: Int, source: SourceId = SourceId.BANGUMI): WorkMatch =
        WorkMatch(
            work = work(id = id, canonical = id, source = source),
            matchConfidence = confidence,
            sourceTag = source,
            popularity = popularity,
        )

    "评分人数显著更多的正片应胜过相似度仅略高的小条目" {
        // 正片 27330 人 vs 第二季 11 人，后者相似度略高也不应顶替。
        val main = match("main", confidence = 0.93f, popularity = 27330)
        val sequel = match("sequel", confidence = 0.96f, popularity = 11)
        representativeOf(listOf(sequel, main)).work.id shouldBe "main"
    }

    "评分人数加成不应让低相似度条目顶替相关正片" {
        val related = match("related", confidence = 0.93f, popularity = 100)
        val unrelatedHot = match("hot", confidence = 0.5f, popularity = 200_000)
        representativeOf(listOf(unrelatedHot, related)).work.id shouldBe "related"
    }

    "评分人数加成不超过上限且人数为零时无加成" {
        val boosted = representativeScore(0.5, 1_000_000)
        (boosted <= 0.5 + POPULARITY_BOOST_MAX + 1e-9) shouldBe true
        (boosted >= 0.5 + POPULARITY_BOOST_MAX - 1e-6) shouldBe true
        representativeScore(0.8, 0) shouldBe 0.8
    }
})
