package com.acgcompass.data.local.mapper

import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.RatingEntity
import com.acgcompass.data.local.entity.SourceLinkEntity
import com.acgcompass.data.local.entity.TagEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.domain.model.AiGenerator
import com.acgcompass.domain.model.AiResult
import com.acgcompass.domain.model.AiTaskType
import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.Priority
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Units
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the Entity ↔ domain-model mappers .
 *
 * Focus areas:
 *  - round-trip fidelity (domain -> entity -> domain) for the core models, and
 *  - safe parsing of unknown / corrupt String-backed enums (never crash, sensible
 *    default) to honor upgrade resilience ().
 */
class MapperTest : StringSpec({

    // ---- Work ---------------------------------------------------------------

    "Work round-trips through entity preserving all fields" {
        val work = Work(
            id = "w1",
            titles = Titles(
                canonical = "规范名",
                ja = "日本語",
                romaji = "Nihongo",
                en = "English",
                aliases = listOf("别名1", "别名2"),
            ),
            mediaType = MediaType.VN,
            year = 2021,
            status = ReleaseStatus.FINISHED,
            units = Units(episodes = 12, episodeMinutes = 24, volumes = null, estPlayMinutes = 3000),
            coverUrl = "https://example.com/c.jpg",
            primarySource = SourceId.VNDB,
            completionCost = CompletionCost.WEEKEND,
            tags = emptyList(),
        )

        val restored = work.toEntity(createdAt = 100L, updatedAt = 200L).toDomain()

        restored shouldBe work
    }

    "WorkEntity with unknown mediaType / status / primarySource / cost falls back safely" {
        val entity = WorkEntity(
            id = "w2",
            canonicalTitle = "t",
            titleJa = null,
            titleRomaji = null,
            titleEn = null,
            aliases = emptyList(),
            mediaType = "NOT_A_TYPE",
            year = null,
            status = "GARBAGE",
            episodes = null,
            episodeMinutes = null,
            volumes = null,
            estPlayMinutes = null,
            coverUrl = null,
            primarySource = "???",
            completionCostBucket = "weird",
            createdAt = 0L,
            updatedAt = 0L,
        )

        val domain = entity.toDomain()

        domain.mediaType shouldBe MediaType.ANIME // safe default
        domain.status shouldBe ReleaseStatus.UNKNOWN // safe default
        domain.primarySource shouldBe SourceId.BANGUMI // safe default
        domain.completionCost.shouldBeNull() // unknown cost -> null ("暂无数据")
    }

    // ---- BacklogItem --------------------------------------------------------

    "BacklogItem round-trips through entity" {
        val item = BacklogItem(
            workId = "w1",
            priority = Priority.HIGH,
            moodTags = listOf("治愈"),
            riskTags = listOf("刀"),
            note = "记得看",
            addedAt = 1234L,
            dustDays = 7,
            inDustMuseum = true,
        )

        item.toEntity().toDomain() shouldBe item
    }

    "BacklogItemEntity with unknown priority falls back to MEDIUM" {
        val entity = BacklogItemEntity(
            workId = "w1",
            priority = "URGENT",
            moodTags = emptyList(),
            riskTags = emptyList(),
            note = null,
            addedAt = 0L,
            dustDays = 0,
            inDustMuseum = false,
        )

        entity.toDomain().priority shouldBe Priority.MEDIUM
    }

    // ---- Tag ----------------------------------------------------------------

    "Tag round-trips and unknown category rows are dropped" {
        val tag = Tag(category = TagCategory.MOOD, name = "热血")
        tag.toEntity(id = "t1").toDomain() shouldBe tag

        val bad = TagEntity(id = "t2", category = "MYSTERY_CATEGORY", name = "x")
        bad.toDomain().shouldBeNull()

        listOf(tag.toEntity("t1"), bad).toDomainList() shouldBe listOf(tag)
    }

    // ---- SourceRef ----------------------------------------------------------

    "SourceLink maps to SourceRef and unknown sourceId is dropped" {
        val entity = SourceLinkEntity(
            id = "l1",
            workId = "w1",
            sourceId = "ANILIST",
            sourceItemId = "123",
            matchConfidence = 0.9f,
            userOverridden = true,
            linkedAt = 0L,
        )

        val ref = entity.toSourceRef()
        ref!!.sourceId shouldBe SourceId.ANILIST
        ref.sourceItemId shouldBe "123"
        ref.matchConfidence shouldBe 0.9f
        ref.userOverridden shouldBe true

        entity.copy(sourceId = "FACEBOOK").toSourceRef().shouldBeNull()
    }

    // ---- Ratings ------------------------------------------------------------

    "Ratings aggregate: missing rows become null and are never back-filled" {
        val present = RatingEntity(
            id = "r1",
            workId = "w1",
            sourceId = "BANGUMI",
            score = 8.2f,
            voteCount = 100,
            rank = 5,
            fetchedAt = 10L,
            missing = false,
        )
        val missing = RatingEntity(
            id = "r2",
            workId = "w1",
            sourceId = "ANILIST",
            score = 0f,
            voteCount = 0,
            rank = null,
            fetchedAt = 10L,
            missing = true,
        )
        val unknownSource = RatingEntity(
            id = "r3",
            workId = "w1",
            sourceId = "UNKNOWN_SRC",
            score = 9f,
            voteCount = 1,
            rank = null,
            fetchedAt = 10L,
            missing = false,
        )

        val agg = listOf(present, missing, unknownSource).toRatingAggregate()

        agg.perSource[SourceId.BANGUMI]?.score shouldBe 8.2f
        agg.perSource.containsKey(SourceId.ANILIST) shouldBe true
        agg.perSource[SourceId.ANILIST].shouldBeNull() // missing stays null
        agg.perSource.containsKey(SourceId.JIKAN) shouldBe false
        agg.consensus.shouldBeNull()
    }

    "Ratings aggregate keeps the latest row per source by fetchedAt" {
        val older = RatingEntity(
            id = "r1",
            workId = "w1",
            sourceId = "BANGUMI",
            score = 7.0f,
            voteCount = 10,
            rank = null,
            fetchedAt = 1L,
            missing = false,
        )
        val newer = RatingEntity(
            id = "r2",
            workId = "w1",
            sourceId = "BANGUMI",
            score = 8.5f,
            voteCount = 50,
            rank = null,
            fetchedAt = 99L,
            missing = false,
        )

        val agg = listOf(older, newer).toRatingAggregate()

        agg.perSource[SourceId.BANGUMI]?.score shouldBe 8.5f
    }

    // ---- AiResult -----------------------------------------------------------

    "AiResult round-trips and dataSources split/join tolerates blanks" {
        val result = AiResult(
            id = "a1",
            workId = "w1",
            taskType = AiTaskType.SPOILER_RADAR,
            generator = AiGenerator.AI,
            payloadJson = "{}",
            confidence = 0.5f,
            dataSources = listOf("BANGUMI", "ANILIST"),
            generatedAt = 42L,
        )

        result.toEntity().toDomain() shouldBe result
    }

    "AiResult with unknown taskType / generator falls back safely" {
        val entity = AiResult(
            id = "a2",
            workId = "w1",
            taskType = AiTaskType.SPOILER_RADAR,
            generator = AiGenerator.AI,
            payloadJson = "{}",
            confidence = 0.5f,
            dataSources = emptyList(),
            generatedAt = 0L,
        ).toEntity().copy(taskType = "??", generator = "??")

        entity.toDomain().taskType shouldBe AiTaskType.UNKNOWN
        entity.toDomain().generator shouldBe AiGenerator.RULE
    }
})
