package com.acgcompass.data.remote.jikan

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

// 覆盖媒介与状态映射、标题选取、年份解析、封面优选和缺失评分语义。
class JikanMappersTest : StringSpec({

    "mapJikanMediaType maps known anime types and falls back to ANIME" {
        mapJikanMediaType(JikanAnimeType.TV) shouldBe MediaType.ANIME
        mapJikanMediaType(JikanAnimeType.MOVIE) shouldBe MediaType.ANIME
        mapJikanMediaType(JikanAnimeType.OVA) shouldBe MediaType.ANIME
        mapJikanMediaType(null) shouldBe MediaType.ANIME
        mapJikanMediaType("Unknown") shouldBe MediaType.ANIME
    }

    "mapJikanMediaType puts theme song, PV and CM entries outside anime" {
        mapJikanMediaType(JikanAnimeType.MUSIC) shouldBe MediaType.OTHER
        mapJikanMediaType(JikanAnimeType.PV) shouldBe MediaType.OTHER
        mapJikanMediaType(JikanAnimeType.CM) shouldBe MediaType.OTHER
        mapJikanMediaType(JikanAnimeType.SPECIAL) shouldBe MediaType.ANIME
    }

    "mapJikanStatus maps known statuses and falls back to UNKNOWN" {
        mapJikanStatus(JikanAnimeStatus.FINISHED) shouldBe ReleaseStatus.FINISHED
        mapJikanStatus(JikanAnimeStatus.AIRING) shouldBe ReleaseStatus.RELEASING
        mapJikanStatus(JikanAnimeStatus.NOT_YET_AIRED) shouldBe ReleaseStatus.NOT_RELEASED
        mapJikanStatus(null) shouldBe ReleaseStatus.UNKNOWN
        mapJikanStatus("garbage") shouldBe ReleaseStatus.UNKNOWN
    }

    "parseJikanYear extracts year prefix from ISO date and rejects garbage" {
        parseJikanYear("1998-04-03T00:00:00+00:00") shouldBe 1998
        parseJikanYear("2011") shouldBe 2011
        parseJikanYear(null).shouldBeNull()
        parseJikanYear("abcd").shouldBeNull()
        parseJikanYear("12").shouldBeNull()
    }

    "resolvedYear prefers explicit year then aired.from" {
        JikanAnimeDto(malId = 1, year = 1998).resolvedYear() shouldBe 1998
        JikanAnimeDto(malId = 1, aired = JikanAiredDto(from = "2006-04-01T00:00:00+00:00"))
            .resolvedYear() shouldBe 2006
        JikanAnimeDto(malId = 1).resolvedYear().shouldBeNull()
    }

    "preferredCover prefers jpg large then jpg normal then webp" {
        JikanImagesDto(
            jpg = JikanImageSetDto(imageUrl = "j", largeImageUrl = "jl"),
            webp = JikanImageSetDto(largeImageUrl = "wl"),
        ).preferredCover() shouldBe "jl"
        JikanImagesDto(jpg = JikanImageSetDto(imageUrl = "j")).preferredCover() shouldBe "j"
        JikanImagesDto(webp = JikanImageSetDto(largeImageUrl = "wl")).preferredCover() shouldBe "wl"
        JikanImagesDto().preferredCover().shouldBeNull()
        null.preferredCover().shouldBeNull()
    }

    "toRatingEntry returns null when score or scored_by missing (no fabrication)" {
        // Jikan 缺失评分以 0 表示——绝不当作 0 分评分。
        JikanAnimeDto(malId = 1, score = 0f, scoredBy = 0).toRatingEntry().shouldBeNull()
        JikanAnimeDto(malId = 1, score = 8.5f, scoredBy = 0).toRatingEntry().shouldBeNull()
        JikanAnimeDto(malId = 1, score = 0f, scoredBy = 100).toRatingEntry().shouldBeNull()
        JikanAnimeDto(malId = 1, score = null, scoredBy = null).toRatingEntry().shouldBeNull()
    }

    "toRatingEntry maps MAL score, scored_by and positive rank" {
        val entry = JikanAnimeDto(malId = 1, score = 8.75f, scoredBy = 1064356, rank = 48)
            .toRatingEntry()
        entry shouldBe RatingEntry(score = 8.75f, voteCount = 1064356, rank = 48)
    }

    "toRatingEntry drops non-positive rank to null" {
        JikanAnimeDto(malId = 1, score = 7f, scoredBy = 10, rank = 0)
            .toRatingEntry()?.rank.shouldBeNull()
    }

    "toWork maps titles, media type, status, year, episodes, cover and Jikan source" {
        val work = JikanAnimeDto(
            malId = 1,
            title = "Cowboy Bebop",
            titleEnglish = "Cowboy Bebop",
            titleJapanese = "カウボーイビバップ",
            titleSynonyms = listOf("CB", ""),
            type = JikanAnimeType.TV,
            status = JikanAnimeStatus.FINISHED,
            episodes = 26,
            year = 1998,
            images = JikanImagesDto(jpg = JikanImageSetDto(largeImageUrl = "cover")),
        ).toWork()

        work.id shouldBe "1"
        work.titles.canonical shouldBe "Cowboy Bebop"
        work.titles.ja shouldBe "カウボーイビバップ"
        work.titles.en shouldBe "Cowboy Bebop"
        work.titles.aliases shouldContainExactly listOf("CB")
        work.mediaType shouldBe MediaType.ANIME
        work.status shouldBe ReleaseStatus.FINISHED
        work.year shouldBe 1998
        work.units.episodes shouldBe 26
        work.coverUrl shouldBe "cover"
        work.primarySource shouldBe SourceId.JIKAN
        work.tags shouldBe emptyList()
    }

    "toWork falls back to english then japanese then id when title missing" {
        JikanAnimeDto(malId = 7, titleEnglish = "English Only").toWork()
            .titles.canonical shouldBe "English Only"
        JikanAnimeDto(malId = 7, titleJapanese = "日本語のみ").toWork()
            .titles.canonical shouldBe "日本語のみ"
        JikanAnimeDto(malId = 7).toWork().titles.canonical shouldBe "7"
    }

    "toWork drops non-positive episodes" {
        JikanAnimeDto(malId = 1, episodes = 0).toWork().units.episodes.shouldBeNull()
    }

    "toWorkMatch carries confidence and Jikan source tag" {
        val match = JikanAnimeDto(malId = 5, title = "测试").toWorkMatch(matchConfidence = 0.66f)
        match.matchConfidence shouldBe 0.66f
        match.sourceTag shouldBe SourceId.JIKAN
        match.work.id shouldBe "5"
        JikanAnimeDto(malId = 5).toWorkMatch().matchConfidence shouldBe 1f
    }
})
