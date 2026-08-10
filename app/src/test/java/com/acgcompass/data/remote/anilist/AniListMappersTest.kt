package com.acgcompass.data.remote.anilist

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

// 覆盖媒介与状态映射、英文标题回退、年份解析、封面优选、
// 缺失评分语义及真实样本数和排名。
class AniListMappersTest : StringSpec({

    "mapAniListMediaType distinguishes anime / manga / novel and falls back to ANIME" {
        mapAniListMediaType("ANIME", "TV") shouldBe MediaType.ANIME
        mapAniListMediaType("MANGA", "MANGA") shouldBe MediaType.MANGA
        mapAniListMediaType("MANGA", "NOVEL") shouldBe MediaType.NOVEL
        mapAniListMediaType(null, null) shouldBe MediaType.ANIME
        mapAniListMediaType("GARBAGE", "X") shouldBe MediaType.ANIME
    }

    "mapAniListStatus maps known statuses and falls back to UNKNOWN" {
        mapAniListStatus("FINISHED") shouldBe ReleaseStatus.FINISHED
        mapAniListStatus("RELEASING") shouldBe ReleaseStatus.RELEASING
        mapAniListStatus("NOT_YET_RELEASED") shouldBe ReleaseStatus.NOT_RELEASED
        mapAniListStatus("CANCELLED") shouldBe ReleaseStatus.CANCELLED
        mapAniListStatus("HIATUS") shouldBe ReleaseStatus.ON_HIATUS
        mapAniListStatus(null) shouldBe ReleaseStatus.UNKNOWN
        mapAniListStatus("garbage") shouldBe ReleaseStatus.UNKNOWN
    }

    "resolveYear prefers seasonYear then startDate.year and rejects out-of-range" {
        AniListMediaDto(id = 1, seasonYear = 2013).resolveYear() shouldBe 2013
        AniListMediaDto(id = 1, startDate = AniListFuzzyDateDto(year = 2006)).resolveYear() shouldBe 2006
        AniListMediaDto(id = 1).resolveYear().shouldBeNull()
        AniListMediaDto(id = 1, seasonYear = 1700).resolveYear().shouldBeNull()
    }

    "preferredCover prefers extraLarge then large then medium" {
        AniListCoverImageDto(extraLarge = "xl", large = "l", medium = "m").preferredCover() shouldBe "xl"
        AniListCoverImageDto(large = "l", medium = "m").preferredCover() shouldBe "l"
        AniListCoverImageDto(medium = "m").preferredCover() shouldBe "m"
        AniListCoverImageDto().preferredCover().shouldBeNull()
        null.preferredCover().shouldBeNull()
    }

    "toRatingEntry returns null when averageScore missing or non-positive (no fabrication)" {
        AniListMediaDto(id = 1, averageScore = null).toRatingEntry().shouldBeNull()
        AniListMediaDto(id = 1, averageScore = 0).toRatingEntry().shouldBeNull()
    }

    "toRatingEntry keeps native 100-scale score, sums scoreDistribution as real vote count, takes all-time RATED rank" {
        val entry = AniListMediaDto(
            id = 1,
            averageScore = 85,
            stats = AniListStatsDto(
                scoreDistribution = listOf(
                    AniListScoreDistributionDto(score = 80, amount = 1200),
                    AniListScoreDistributionDto(score = 90, amount = 800),
                ),
            ),
            rankings = listOf(
                AniListRankingDto(rank = 12, type = "POPULAR", allTime = true),
                AniListRankingDto(rank = 5, type = "RATED", allTime = true),
                AniListRankingDto(rank = 1, type = "RATED", allTime = false),
            ),
        ).toRatingEntry()

        entry shouldBe RatingEntry(score = 85f, voteCount = 2000, rank = 5)
    }

    "toRatingEntry leaves vote count 0 and rank null when stats / rankings absent (no fabrication)" {
        val entry = AniListMediaDto(id = 1, averageScore = 70).toRatingEntry()
        entry shouldBe RatingEntry(score = 70f, voteCount = 0, rank = null)
    }

    "toWork uses english title as canonical for Chinese fallback then romaji then native then id ()" {
        AniListMediaDto(
            id = 1,
            title = AniListTitleDto(romaji = "Shingeki", english = "Attack on Titan", nativeTitle = "進撃の巨人"),
        ).toWork().titles.canonical shouldBe "Attack on Titan"

        AniListMediaDto(id = 1, title = AniListTitleDto(romaji = "Romaji Only")).toWork()
            .titles.canonical shouldBe "Romaji Only"

        AniListMediaDto(id = 1, title = AniListTitleDto(nativeTitle = "原文のみ")).toWork()
            .titles.canonical shouldBe "原文のみ"

        AniListMediaDto(id = 42).toWork().titles.canonical shouldBe "42"
    }

    "toWork maps titles, media type, status, year, units, cover and AniList source" {
        val work = AniListMediaDto(
            id = 16498,
            type = "ANIME",
            format = "TV",
            status = "FINISHED",
            title = AniListTitleDto(romaji = "Shingeki no Kyojin", english = "Attack on Titan", nativeTitle = "進撃の巨人"),
            seasonYear = 2013,
            episodes = 25,
            duration = 24,
            coverImage = AniListCoverImageDto(extraLarge = "cover"),
        ).toWork()

        work.id shouldBe "16498"
        work.titles.en shouldBe "Attack on Titan"
        work.titles.romaji shouldBe "Shingeki no Kyojin"
        work.titles.ja shouldBe "進撃の巨人"
        work.mediaType shouldBe MediaType.ANIME
        work.status shouldBe ReleaseStatus.FINISHED
        work.year shouldBe 2013
        work.units.episodes shouldBe 25
        work.units.episodeMinutes shouldBe 24
        work.coverUrl shouldBe "cover"
        work.primarySource shouldBe SourceId.ANILIST
    }

    "toWork drops non-positive episodes / volumes" {
        val work = AniListMediaDto(id = 1, episodes = 0, volumes = 0).toWork()
        work.units.episodes.shouldBeNull()
        work.units.volumes.shouldBeNull()
    }

    "toWorkMatch carries confidence and AniList source tag" {
        val match = AniListMediaDto(
            id = 5,
            title = AniListTitleDto(english = "Test"),
        ).toWorkMatch(matchConfidence = 0.66f)
        match.matchConfidence shouldBe 0.66f
        match.sourceTag shouldBe SourceId.ANILIST
        match.work.id shouldBe "5"
        AniListMediaDto(id = 5).toWorkMatch().matchConfidence shouldBe 1f
    }
})
