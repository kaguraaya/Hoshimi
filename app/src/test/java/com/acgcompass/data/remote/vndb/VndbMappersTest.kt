package com.acgcompass.data.remote.vndb

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.RatingEntry
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

// 覆盖成人内容分级判定、年份解析、标题选取和评分「不伪造」语义。
// 覆盖成人内容过滤和 Work/WorkMatch 映射（MediaType.VN）。
class VndbMappersTest : StringSpec({

    "isAdult flags VN when image.sexual >= threshold and treats missing image/score as non-adult" {
        // 阈值为 1.0：0=安全 / 1=暗示 / 2=露骨。
        VndbVnDto(id = "v1", image = VndbImageDto(sexual = 2.0f)).isAdult() shouldBe true
        VndbVnDto(id = "v1", image = VndbImageDto(sexual = 1.0f)).isAdult() shouldBe true
        VndbVnDto(id = "v1", image = VndbImageDto(sexual = 0.5f)).isAdult() shouldBe false
        VndbVnDto(id = "v1", image = VndbImageDto(sexual = 0f)).isAdult() shouldBe false
        // 无成人评分 / 无图：视为非成人（不臆造）。
        VndbVnDto(id = "v1", image = VndbImageDto(sexual = null)).isAdult() shouldBe false
        VndbVnDto(id = "v1", image = null).isAdult() shouldBe false
    }

    "R18 gate also covers screenshots and erotic tags" {
        VndbVnDto(
            id = "v1",
            screenshots = listOf(VndbImageDto(sexual = 1.2f)),
        ).isAdult() shouldBe true
        VndbVnDto(
            id = "v2",
            tags = listOf(VndbTagDto(id = "g1", category = "ero", rating = 1.5f)),
        ).isAdult() shouldBe true
        VndbVnDto(
            id = "v3",
            tags = listOf(VndbTagDto(id = "g1", category = "ero", rating = 1.5f, lie = true)),
        ).isAdult() shouldBe false
    }

    "parseVndbYear extracts year prefix from VNDB date strings and rejects garbage" {
        parseVndbYear("2011-06-30") shouldBe 2011
        parseVndbYear("2011-06") shouldBe 2011
        parseVndbYear("2011") shouldBe 2011
        parseVndbYear("TBA").shouldBeNull()
        parseVndbYear(null).shouldBeNull()
        parseVndbYear("abcd").shouldBeNull()
        parseVndbYear("12").shouldBeNull()
    }

    "originalScriptTitle prefers olang title then main then alttitle" {
        VndbVnDto(
            id = "v1",
            olang = "ja",
            titles = listOf(
                VndbTitleDto(lang = "en", title = "English"),
                VndbTitleDto(lang = "ja", title = "日本語", main = true),
            ),
        ).originalScriptTitle() shouldBe "日本語"

        VndbVnDto(
            id = "v1",
            altTitle = "原題",
            titles = emptyList(),
        ).originalScriptTitle() shouldBe "原題"

        VndbVnDto(id = "v1").originalScriptTitle().shouldBeNull()
    }

    "romajiTitle takes latin of olang/main title" {
        VndbVnDto(
            id = "v1",
            olang = "ja",
            titles = listOf(
                VndbTitleDto(lang = "ja", title = "日本語", latin = "Nihongo", main = true),
            ),
        ).romajiTitle() shouldBe "Nihongo"

        VndbVnDto(id = "v1").romajiTitle().shouldBeNull()
    }

    "toRatingEntry returns null when rating missing or votecount non-positive (no fabrication)" {
        // VNDB 无人投票时 rating 为 null——绝不伪造为 0 分。
        VndbVnDto(id = "v1", rating = null, voteCount = null).toRatingEntry().shouldBeNull()
        VndbVnDto(id = "v1", rating = null, voteCount = 100).toRatingEntry().shouldBeNull()
        VndbVnDto(id = "v1", rating = 80f, voteCount = 0).toRatingEntry().shouldBeNull()
        VndbVnDto(id = "v1", rating = 0f, voteCount = 100).toRatingEntry().shouldBeNull()
    }

    "toRatingEntry preserves 10-100 source scale with null rank" {
        val entry = VndbVnDto(id = "v1", rating = 85f, voteCount = 1234).toRatingEntry()
        entry shouldBe RatingEntry(score = 85f, voteCount = 1234, rank = null)
    }

    "toWork maps titles, VN media type, year, play minutes, cover and VNDB source" {
        val work = VndbVnDto(
            id = "v17",
            title = "Ever17",
            olang = "ja",
            titles = listOf(
                VndbTitleDto(lang = "ja", title = "Ever17 -the out of infinity-", latin = "Ever17", main = true),
            ),
            aliases = listOf("E17", ""),
            released = "2002-08-29",
            lengthMinutes = 3000,
            image = VndbImageDto(url = "cover"),
        ).toWork()

        work.id shouldBe "v17"
        work.titles.canonical shouldBe "Ever17 -the out of infinity-"
        work.titles.ja shouldBe "Ever17 -the out of infinity-"
        work.titles.romaji shouldBe "Ever17"
        work.titles.aliases shouldContainExactly listOf("E17")
        work.mediaType shouldBe MediaType.VN
        work.year shouldBe 2002
        work.status shouldBe ReleaseStatus.UNKNOWN
        work.units.estPlayMinutes shouldBe 3000
        work.coverUrl shouldBe "cover"
        work.primarySource shouldBe SourceId.VNDB
        work.tags shouldBe emptyList()
    }

    "toWork falls back to original title then id when main title missing" {
        VndbVnDto(id = "v9", altTitle = "原題").toWork().titles.canonical shouldBe "原題"
        VndbVnDto(id = "v9").toWork().titles.canonical shouldBe "v9"
    }

    "display title prefers simplified then traditional Chinese and falls back to original script" {
        VndbVnDto(
            id = "v1",
            title = "English title",
            olang = "ja",
            titles = listOf(
                VndbTitleDto(lang = "ja", title = "原題", main = true),
                VndbTitleDto(lang = "zh-Hant", title = "繁體標題"),
                VndbTitleDto(lang = "zh-Hans", title = "简体标题"),
            ),
        ).preferredDisplayTitle() shouldBe "简体标题"

        VndbVnDto(
            id = "v2",
            title = "English title",
            olang = "ja",
            titles = listOf(VndbTitleDto(lang = "ja", title = "原題", main = true)),
        ).preferredDisplayTitle() shouldBe "原題"
    }

    "toWork drops non-positive play minutes" {
        VndbVnDto(id = "v1", lengthMinutes = 0).toWork().units.estPlayMinutes.shouldBeNull()
    }

    "toWorkMatch carries confidence and VNDB source tag" {
        val match = VndbVnDto(id = "v5", title = "测试").toWorkMatch(matchConfidence = 0.42f)
        match.matchConfidence shouldBe 0.42f
        match.sourceTag shouldBe SourceId.VNDB
        match.work.id shouldBe "v5"
        VndbVnDto(id = "v5").toWorkMatch().matchConfidence shouldBe 1f
    }

    "detail mapping keeps dimensions, credits, relations and screenshot sensitivity" {
        val detail = VndbVnDto(
            id = "v17",
            title = "Ever17",
            languages = listOf("ja", "en"),
            platforms = listOf("win"),
            rating = 85f,
            voteCount = 1234,
            image = VndbImageDto(url = "cover", dimensions = listOf(600, 900)),
            screenshots = listOf(
                VndbImageDto(url = "shot", thumbnail = "thumb", dimensions = listOf(1280, 720), sexual = 1f),
            ),
            developers = listOf(VndbProducerDto(id = "p1", name = "KID")),
            staff = listOf(VndbStaffDto(id = "s1", name = "Writer", role = "scenario")),
            relations = listOf(VndbRelationDto(id = "v2", title = "Related", relation = "seq", official = true)),
        ).toVisualNovelDetail()

        detail.cover?.width shouldBe 600
        detail.cover?.height shouldBe 900
        detail.screenshots.single().sensitive shouldBe true
        detail.developers.single().name shouldBe "KID"
        detail.staff.single().role shouldBe "scenario"
        detail.relations.single().official shouldBe true
        detail.languages shouldContainExactly listOf("ja", "en")
        detail.platforms shouldContainExactly listOf("win")
    }
})
