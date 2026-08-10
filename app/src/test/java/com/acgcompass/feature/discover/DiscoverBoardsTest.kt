package com.acgcompass.feature.discover

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingAggregate
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * ）。覆盖纯 Kotlin 逻辑：评分差距计算 [scoreSpread] / 归一化 [normalizeScore]、
 * 评分差异榜 [buildScoreDiffBoard]、各源榜单 [buildRankingBoards] 与高级筛选 [applyFilter]。
 */
class DiscoverBoardsTest : StringSpec({

    fun work(
        id: String,
        mediaType: MediaType = MediaType.ANIME,
        year: Int? = 2023,
        status: ReleaseStatus = ReleaseStatus.FINISHED,
        primary: SourceId = SourceId.BANGUMI,
        tags: List<Tag> = emptyList(),
    ): Work = Work(
        id = id,
        titles = Titles(canonical = "作品-$id"),
        mediaType = mediaType,
        year = year,
        status = status,
        primarySource = primary,
        tags = tags,
    )

    fun entry(score: Float, votes: Int = 100): RatingEntry = RatingEntry(score = score, voteCount = votes)

    // region 归一化与评分差距

    "normalizeScore 把 AniList/VNDB 的 0–100 标度缩放到 0–10" {
        normalizeScore(SourceId.ANILIST, 82f) shouldBe 8.2f
        normalizeScore(SourceId.VNDB, 65f) shouldBe 6.5f
    }

    "normalizeScore 保持 Bangumi/MAL/Jikan 的原生 0–10 标度" {
        normalizeScore(SourceId.BANGUMI, 8.1f) shouldBe 8.1f
        normalizeScore(SourceId.MAL, 7.3f) shouldBe 7.3f
        normalizeScore(SourceId.JIKAN, 6.9f) shouldBe 6.9f
    }

    "scoreSpread 取归一化后的最高与最低之差" {
        val spread = scoreSpread(
            mapOf(
                SourceId.BANGUMI to entry(8.0f),
                SourceId.ANILIST to entry(60f), // -> 6.0
            ),
        )
        spread!!.toDouble() shouldBe (2.0 plusOrMinus 1e-4)
    }

    "scoreSpread 在有效源少于 2 个时返回 null（不伪造差距）" {
        scoreSpread(emptyMap()).shouldBeNull()
        scoreSpread(mapOf(SourceId.BANGUMI to entry(8.0f))).shouldBeNull()
        scoreSpread(
            mapOf(
                SourceId.BANGUMI to entry(8.0f),
                SourceId.MAL to null,
            ),
        ).shouldBeNull()
    }

    "scoreSpread 忽略缺失源，仅用有效源计算" {
        val spread = scoreSpread(
            mapOf(
                SourceId.BANGUMI to entry(9.0f),
                SourceId.ANILIST to null,
                SourceId.MAL to entry(7.0f),
            ),
        )
        spread!!.toDouble() shouldBe (2.0 plusOrMinus 1e-4)
    }

    // endregion

    // region 评分差异榜

    "buildScoreDiffBoard 仅保留差距达到阈值的作品并按差距降序" {
        val works = listOf(
            // 差距 3.0（大）
            WorkRatings(
                work = work("big"),
                ratings = RatingAggregate(
                    perSource = mapOf(
                        SourceId.BANGUMI to entry(9.0f),
                        SourceId.MAL to entry(6.0f),
                    ),
                ),
            ),
            // 差距 0.5（小，过滤掉）
            WorkRatings(
                work = work("small"),
                ratings = RatingAggregate(
                    perSource = mapOf(
                        SourceId.BANGUMI to entry(8.0f),
                        SourceId.MAL to entry(7.5f),
                    ),
                ),
            ),
            // 差距 2.0（中，保留）
            WorkRatings(
                work = work("mid"),
                ratings = RatingAggregate(
                    perSource = mapOf(
                        SourceId.BANGUMI to entry(8.0f),
                        SourceId.ANILIST to entry(60f),
                    ),
                ),
            ),
        )

        val board = buildScoreDiffBoard(works)

        board.map { it.workId } shouldContainExactly listOf("big", "mid")
        board.first().spreadLabel shouldBe "评分差距 3.0"
    }

    "buildScoreDiffBoard 跳过无评分聚合的作品" {
        val works = listOf(WorkRatings(work = work("none"), ratings = null))
        buildScoreDiffBoard(works).shouldHaveSize(0)
    }

    // endregion

    // region 各源榜单

    "buildRankingBoards 为四个源各成榜，按该源评分降序" {
        val works = listOf(
            WorkRatings(
                work("a"),
                RatingAggregate(perSource = mapOf(SourceId.BANGUMI to entry(7.0f))),
            ),
            WorkRatings(
                work("b"),
                RatingAggregate(perSource = mapOf(SourceId.BANGUMI to entry(9.0f))),
            ),
        )

        val boards = buildRankingBoards(works)
        boards.map { it.source } shouldContainExactly RANKING_SOURCES

        val bangumi = boards.first { it.source == SourceId.BANGUMI }
        bangumi.isPlaceholder shouldBe false
        bangumi.items.first().card.title shouldBe "作品-b" // 9.0 在前
    }

    "buildRankingBoards 在某源无任何评分时标记为占位（暂无数据）" {
        val works = listOf(
            WorkRatings(work("a"), RatingAggregate(perSource = mapOf(SourceId.BANGUMI to entry(8.0f)))),
        )
        val boards = buildRankingBoards(works)
        boards.first { it.source == SourceId.MAL }.isPlaceholder shouldBe true
    }

    // endregion

    // region 高级筛选

    "applyFilter 空筛选返回全部" {
        val works = listOf(WorkRatings(work("a")), WorkRatings(work("b")))
        applyFilter(works, DiscoverFilter()) shouldHaveSize 2
    }

    "applyFilter 按类型过滤" {
        val works = listOf(
            WorkRatings(work("anime", mediaType = MediaType.ANIME)),
            WorkRatings(work("manga", mediaType = MediaType.MANGA)),
        )
        val result = applyFilter(works, DiscoverFilter(types = setOf(MediaType.MANGA)))
        result.map { it.work.id } shouldContainExactly listOf("manga")
    }

    "applyFilter 按完结状态过滤" {
        val works = listOf(
            WorkRatings(work("done", status = ReleaseStatus.FINISHED)),
            WorkRatings(work("airing", status = ReleaseStatus.RELEASING)),
        )
        applyFilter(works, DiscoverFilter(finished = FinishedFilter.ONGOING))
            .map { it.work.id } shouldContainExactly listOf("airing")
        applyFilter(works, DiscoverFilter(finished = FinishedFilter.FINISHED))
            .map { it.work.id } shouldContainExactly listOf("done")
    }

    "applyFilter 按最低评分过滤（归一化）" {
        val works = listOf(
            WorkRatings(
                work("high"),
                RatingAggregate(perSource = mapOf(SourceId.ANILIST to entry(90f))), // 9.0
            ),
            WorkRatings(
                work("low"),
                RatingAggregate(perSource = mapOf(SourceId.BANGUMI to entry(6.0f))),
            ),
            WorkRatings(work("none"), ratings = null),
        )
        applyFilter(works, DiscoverFilter(minRating = 8f))
            .map { it.work.id } shouldContainExactly listOf("high")
    }

    "applyFilter 按风险与心情标签过滤" {
        val risky = work("risky", tags = listOf(Tag(TagCategory.RISK, "刀")))
        val healing = work("healing", tags = listOf(Tag(TagCategory.MOOD, "治愈")))
        val works = listOf(WorkRatings(risky), WorkRatings(healing))

        applyFilter(works, DiscoverFilter(riskTags = setOf("刀")))
            .map { it.work.id } shouldContainExactly listOf("risky")
        applyFilter(works, DiscoverFilter(moodTags = setOf("治愈")))
            .map { it.work.id } shouldContainExactly listOf("healing")
    }

    "applyFilter 按来源平台过滤（主源或有评分的源）" {
        val works = listOf(
            WorkRatings(work("bgm", primary = SourceId.BANGUMI)),
            WorkRatings(
                work("anilistRated", primary = SourceId.BANGUMI),
                RatingAggregate(perSource = mapOf(SourceId.ANILIST to entry(80f))),
            ),
        )
        applyFilter(works, DiscoverFilter(sources = setOf(SourceId.ANILIST)))
            .map { it.work.id } shouldContainExactly listOf("anilistRated")
    }

    "buildFilterFacets 归纳年份与标签去重排序" {
        val works = listOf(
            WorkRatings(work("a", year = 2020, tags = listOf(Tag(TagCategory.LENGTH, "短篇")))),
            WorkRatings(work("b", year = 2022, tags = listOf(Tag(TagCategory.LENGTH, "短篇")))),
        )
        val facets = buildFilterFacets(works)
        facets.years shouldContainExactly listOf(2022, 2020)
        facets.lengths shouldContainExactly listOf("短篇")
    }

    // endregion
})
