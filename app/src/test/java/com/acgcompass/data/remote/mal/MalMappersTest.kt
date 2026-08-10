package com.acgcompass.data.remote.mal

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

// 覆盖媒介与状态映射、标题选取、年份解析、封面优选和缺失评分语义。
class MalMappersTest : StringSpec({

    "mapMalMediaType maps known media types and falls back to ANIME" {
        mapMalMediaType(MalMediaType.TV) shouldBe MediaType.ANIME
        mapMalMediaType(MalMediaType.MOVIE) shouldBe MediaType.ANIME
        mapMalMediaType(MalMediaType.OVA) shouldBe MediaType.ANIME
        mapMalMediaType("TV") shouldBe MediaType.ANIME // 大小写不敏感
        mapMalMediaType(null) shouldBe MediaType.ANIME
        mapMalMediaType("garbage") shouldBe MediaType.ANIME
    }

    "mapMalStatus maps known statuses and falls back to UNKNOWN" {
        mapMalStatus(MalAnimeStatus.FINISHED) shouldBe ReleaseStatus.FINISHED
        mapMalStatus(MalAnimeStatus.AIRING) shouldBe ReleaseStatus.RELEASING
        mapMalStatus(MalAnimeStatus.NOT_YET_AIRED) shouldBe ReleaseStatus.NOT_RELEASED
        mapMalStatus("FINISHED_AIRING") shouldBe ReleaseStatus.FINISHED // 大小写不敏感
        mapMalStatus(null) shouldBe ReleaseStatus.UNKNOWN
        mapMalStatus("garbage") shouldBe ReleaseStatus.UNKNOWN
    }

    "resolvedYear takes start_season.year within sane range" {
        MalAnimeDto(id = 1, startSeason = MalStartSeasonDto(year = 1998)).resolvedYear() shouldBe 1998
        MalAnimeDto(id = 1, startSeason = MalStartSeasonDto(year = 1800)).resolvedYear().shouldBeNull()
        MalAnimeDto(id = 1).resolvedYear().shouldBeNull()
    }

    "preferredCover prefers large then medium" {
        MalPictureDto(medium = "m", large = "l").preferredCover() shouldBe "l"
        MalPictureDto(medium = "m").preferredCover() shouldBe "m"
        MalPictureDto().preferredCover().shouldBeNull()
        null.preferredCover().shouldBeNull()
    }

    "toRatingEntry returns null when mean or scoring users missing (no fabrication)" {
        // mean 缺失即「暂无评分」——绝不以 0 分伪造。
        MalAnimeDto(id = 1, mean = null, numScoringUsers = 1000).toRatingEntry().shouldBeNull()
        MalAnimeDto(id = 1, mean = 0f, numScoringUsers = 1000).toRatingEntry().shouldBeNull()
        MalAnimeDto(id = 1, mean = 8.5f, numScoringUsers = 0).toRatingEntry().shouldBeNull()
        MalAnimeDto(id = 1, mean = 8.5f, numScoringUsers = null).toRatingEntry().shouldBeNull()
    }

    "toRatingEntry maps mean, scoring users and positive rank" {
        val entry = MalAnimeDto(id = 1, mean = 8.75f, numScoringUsers = 1064356, rank = 48)
            .toRatingEntry()
        entry shouldBe RatingEntry(score = 8.75f, voteCount = 1064356, rank = 48)
    }

    "toRatingEntry drops non-positive rank to null" {
        MalAnimeDto(id = 1, mean = 7f, numScoringUsers = 10, rank = 0)
            .toRatingEntry()?.rank.shouldBeNull()
    }

    "toWork maps titles, media type, status, year, episodes, cover and MAL source" {
        val work = MalAnimeDto(
            id = 1,
            title = "Cowboy Bebop",
            alternativeTitles = MalAlternativeTitlesDto(
                en = "Cowboy Bebop",
                ja = "カウボーイビバップ",
                synonyms = listOf("CB", ""),
            ),
            mediaType = MalMediaType.TV,
            status = MalAnimeStatus.FINISHED,
            numEpisodes = 26,
            startSeason = MalStartSeasonDto(year = 1998, season = "spring"),
            mainPicture = MalPictureDto(medium = "m", large = "cover"),
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
        work.primarySource shouldBe SourceId.MAL
        work.tags shouldBe emptyList()
    }

    "toWork falls back to english then japanese then id when title missing" {
        MalAnimeDto(id = 7, alternativeTitles = MalAlternativeTitlesDto(en = "English Only"))
            .toWork().titles.canonical shouldBe "English Only"
        MalAnimeDto(id = 7, alternativeTitles = MalAlternativeTitlesDto(ja = "日本語のみ"))
            .toWork().titles.canonical shouldBe "日本語のみ"
        MalAnimeDto(id = 7).toWork().titles.canonical shouldBe "7"
    }

    "toWork drops non-positive episodes" {
        MalAnimeDto(id = 1, numEpisodes = 0).toWork().units.episodes.shouldBeNull()
    }

    "toWorkMatch carries confidence and MAL source tag" {
        val match = MalAnimeDto(id = 5, title = "测试").toWorkMatch(matchConfidence = 0.66f)
        match.matchConfidence shouldBe 0.66f
        match.sourceTag shouldBe SourceId.MAL
        match.work.id shouldBe "5"
        MalAnimeDto(id = 5).toWorkMatch().matchConfidence shouldBe 1f
    }

    "toWorkMatchOrNull unwraps node and returns null when node missing" {
        MalUserListNodeDto(node = MalAnimeDto(id = 9, title = "N")).toWorkMatchOrNull()
            ?.work?.id shouldBe "9"
        MalUserListNodeDto(node = null).toWorkMatchOrNull().shouldBeNull()
    }

    "search node toWorkMatchOrNull unwraps node and returns null when node missing" {
        MalAnimeListNodeDto(node = MalAnimeDto(id = 11, title = "S")).toWorkMatchOrNull()
            ?.work?.id shouldBe "11"
        MalAnimeListNodeDto(node = null).toWorkMatchOrNull().shouldBeNull()
    }
})
