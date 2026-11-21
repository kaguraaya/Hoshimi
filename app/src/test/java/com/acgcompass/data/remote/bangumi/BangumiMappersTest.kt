package com.acgcompass.data.remote.bangumi

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.taste.WorkFeatureCompleteness
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

// 覆盖媒介类型映射、标题选取、体量与年份解析、封面优选和缺失评分语义。
class BangumiMappersTest : StringSpec({

    // 音乐、三次元和未知类型不能误入动画候选池。
    "mapBangumiMediaType maps known types; 音乐/三次元/未知归 OTHER" {
        mapBangumiMediaType(BangumiSubjectType.ANIME) shouldBe MediaType.ANIME
        mapBangumiMediaType(BangumiSubjectType.BOOK) shouldBe MediaType.MANGA
        mapBangumiMediaType(BangumiSubjectType.GAME) shouldBe MediaType.GAME
        mapBangumiMediaType(BangumiSubjectType.MUSIC) shouldBe MediaType.OTHER
        mapBangumiMediaType(BangumiSubjectType.REAL) shouldBe MediaType.OTHER
        mapBangumiMediaType(null) shouldBe MediaType.OTHER
        mapBangumiMediaType(999) shouldBe MediaType.OTHER
    }

    "rating histogram reads 1–10 buckets and treats missing or all-zero counts as unknown" {
        BangumiRatingDto(total = 6, count = mapOf("10" to 1, "8" to 3, "1" to 2), score = 7f).histogram() shouldBe
            listOf(2, 0, 0, 0, 0, 0, 0, 3, 0, 1)
        BangumiRatingDto(total = 0, count = null).histogram() shouldBe emptyList()
        BangumiRatingDto(total = 0, count = mapOf("5" to 0)).histogram() shouldBe emptyList()
    }

    "parseBangumiYear extracts year prefix and rejects garbage" {
        parseBangumiYear("2023-04-07") shouldBe 2023
        parseBangumiYear("1998") shouldBe 1998
        parseBangumiYear(null).shouldBeNull()
        parseBangumiYear("abcd").shouldBeNull()
        parseBangumiYear("12").shouldBeNull()
    }

    "preferredCover prefers large then common then medium/grid/small" {
        BangumiImagesDto(small = "s", grid = "g", large = "l", medium = "m", common = "c")
            .preferredCover() shouldBe "l"
        BangumiImagesDto(small = "s", grid = "g", medium = "m", common = "c")
            .preferredCover() shouldBe "c"
        BangumiImagesDto(small = "s").preferredCover() shouldBe "s"
        BangumiImagesDto().preferredCover().shouldBeNull()
        null.preferredCover().shouldBeNull()
    }

    "toRatingEntry returns null when no votes (no fabrication)" {
        BangumiRatingDto(score = 0f, total = 0).toRatingEntry().shouldBeNull()
        null.toRatingEntry().shouldBeNull()
    }

    "toRatingEntry maps score, voteCount and positive rank" {
        val entry = BangumiRatingDto(rank = 12, total = 3456, score = 8.4f).toRatingEntry()
        entry shouldBe com.acgcompass.domain.model.RatingEntry(
            score = 8.4f,
            voteCount = 3456,
            rank = 12,
        )
    }

    "toRatingEntry drops non-positive rank to null" {
        BangumiRatingDto(rank = 0, total = 10, score = 5f).toRatingEntry()?.rank.shouldBeNull()
    }

    "toWork prefers Chinese title as canonical and keeps original as ja" {
        val work = BangumiSubjectDto(
            id = 42,
            type = BangumiSubjectType.ANIME,
            name = "シュタインズ・ゲート",
            nameCn = "命运石之门",
            date = "2011-04-06",
            eps = 24,
            images = BangumiImagesDto(large = "cover"),
        ).toWork()

        work.id shouldBe "42"
        work.titles.canonical shouldBe "命运石之门"
        work.titles.ja shouldBe "シュタインズ・ゲート"
        work.mediaType shouldBe MediaType.ANIME
        work.year shouldBe 2011
        work.units.episodes shouldBe 24
        work.coverUrl shouldBe "cover"
        work.primarySource shouldBe SourceId.BANGUMI
        work.tags shouldBe emptyList()
    }

    "toWork reads aliases from the infobox and skips names equal to the titles" {
        val json = com.acgcompass.core.network.NetworkJson.instance
        val dto = json.decodeFromString(
            BangumiSubjectDto.serializer(),
            """
            {"id": 328609, "type": 2, "name": "ぼっち・ざ・ろっく！", "name_cn": "孤独摇滚！",
             "infobox": [
               {"key": "中文名", "value": "孤独摇滚！"},
               {"key": "别名", "value": [{"v": "BTR"}, {"v": "Bocchi the Rock!"}, {"v": "孤独摇滚！"}]},
               {"key": "放送开始", "value": "2022年10月8日"}
             ]}
            """.trimIndent(),
        )
        dto.toWork().titles.aliases shouldBe listOf("BTR", "Bocchi the Rock!")
    }

    "toWork falls back to original name then id when Chinese name missing" {
        BangumiSubjectDto(id = 7, name = "Original Only").toWork().titles.canonical shouldBe "Original Only"
        BangumiSubjectDto(id = 7).toWork().titles.canonical shouldBe "7"
    }

    "toWork uses volumes for books and drops non-positive units" {
        val book = BangumiSubjectDto(
            id = 1,
            type = BangumiSubjectType.BOOK,
            volumes = 10,
            eps = 0,
        ).toWork()
        book.mediaType shouldBe MediaType.MANGA
        book.units.volumes shouldBe 10
        book.units.episodes.shouldBeNull()
    }

    "toWorkMatch carries confidence and Bangumi source tag" {
        val match = BangumiSubjectDto(id = 5, nameCn = "测试").toWorkMatch(matchConfidence = 0.72f)
        match.matchConfidence shouldBe 0.72f
        match.sourceTag shouldBe SourceId.BANGUMI
        match.work.id shouldBe "5"
        BangumiSubjectDto(id = 5).toWorkMatch().matchConfidence shouldBe 1f
    }

    "toDomainTags filters blank names and applies category" {
        val tags = listOf(
            BangumiTagDto(name = "治愈", count = 100),
            BangumiTagDto(name = "", count = 5),
        ).toDomainTags(TagCategory.MOOD)
        tags.size shouldBe 1
        tags.first().name shouldBe "治愈"
        tags.first().category shouldBe TagCategory.MOOD
    }

    "subject detail creates a BASIC feature with tag vote counts" {
        val feature = BangumiSubjectDto(
            id = 42,
            type = BangumiSubjectType.ANIME,
            name = "Original",
            nameCn = "中文标题",
            eps = 12,
            tags = listOf(BangumiTagDto("治愈", 321), BangumiTagDto("", 99)),
            rating = BangumiRatingDto(score = 8.1f, total = 1200),
        ).toBasicWorkFeature(updatedAt = 123L)

        feature?.subjectId shouldBe "42"
        feature?.tagCounts?.single()?.name shouldBe "治愈"
        feature?.tagCounts?.single()?.count shouldBe 321
        feature?.titleVariants shouldBe listOf("Original", "中文标题")
        feature?.completeness shouldBe WorkFeatureCompleteness.BASIC
        feature?.baseDataUpdatedAt shouldBe 123L
    }
})
