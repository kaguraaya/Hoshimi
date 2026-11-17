package com.acgcompass.data.repository

import com.acgcompass.data.local.entity.WorkFeatureEntity
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.taste.TagCount
import com.acgcompass.domain.taste.WorkFeature
import com.acgcompass.domain.taste.WorkFeatureCompleteness
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WorkFeatureMergeTest : StringSpec({
    "identical embedded tags preserve a verified basic snapshot" {
        val existing = entity(completeness = WorkFeatureCompleteness.BASIC, tagsJson = TAGS_JSON)

        val merged = mergeLightweightFeature(feature(), existing, TAGS_JSON, NOW)

        merged.completeness shouldBe WorkFeatureCompleteness.BASIC.name
        merged.baseDataUpdatedAt shouldBe BASE_UPDATED_AT
    }

    "identical embedded tags preserve extended data" {
        val existing = entity(completeness = WorkFeatureCompleteness.EXTENDED, tagsJson = TAGS_JSON)

        val merged = mergeLightweightFeature(feature(), existing, TAGS_JSON, NOW)

        merged.completeness shouldBe WorkFeatureCompleteness.EXTENDED.name
        merged.staff shouldBe listOf("staff")
        merged.characters shouldBe listOf("character")
        merged.cv shouldBe listOf("cv")
    }

    "truncated embedded tags never replace a verified snapshot" {
        val existing = entity(completeness = WorkFeatureCompleteness.EXTENDED, tagsJson = TAGS_JSON)

        val merged = mergeLightweightFeature(feature(votes = 0), existing, CHANGED_TAGS_JSON, NOW)

        merged.completeness shouldBe WorkFeatureCompleteness.EXTENDED.name
        merged.tagCountsJson shouldBe TAGS_JSON
        merged.baseDataUpdatedAt shouldBe BASE_UPDATED_AT
        merged.bangumiVotes shouldBe 100
        merged.staff shouldBe listOf("staff")
    }

    "embedded data without a score keeps the cached community score" {
        val existing = entity(completeness = WorkFeatureCompleteness.BASIC, tagsJson = TAGS_JSON)

        val merged = mergeLightweightFeature(feature(score = null), existing, CHANGED_TAGS_JSON, NOW)

        merged.bangumiScore shouldBe 8f
    }

    "a verified basic snapshot with new tags replaces the old one" {
        val existing = entity(completeness = WorkFeatureCompleteness.BASIC, tagsJson = TAGS_JSON)
        val incoming = feature().copy(completeness = WorkFeatureCompleteness.BASIC)

        val merged = mergeLightweightFeature(incoming, existing, CHANGED_TAGS_JSON, NOW)

        merged.tagCountsJson shouldBe CHANGED_TAGS_JSON
        merged.baseDataUpdatedAt shouldBe NOW
    }

    "embedded tags fill an empty cache" {
        val merged = mergeLightweightFeature(feature(), null, CHANGED_TAGS_JSON, NOW)

        merged.completeness shouldBe WorkFeatureCompleteness.EMBEDDED.name
        merged.tagCountsJson shouldBe CHANGED_TAGS_JSON
    }
}) {
    companion object {
        private fun feature(score: Float? = 8f, votes: Int = 100) = WorkFeature(
            subjectId = "1",
            tagCounts = listOf(TagCount("治愈", 3)),
            bangumiScore = score,
            bangumiVotes = votes,
            eps = 12,
            platform = "TV",
            mediaType = MediaType.ANIME,
            titleVariants = listOf("标题"),
            completeness = WorkFeatureCompleteness.EMBEDDED,
            baseDataUpdatedAt = NOW,
            updatedAt = NOW,
        )

        private fun entity(completeness: WorkFeatureCompleteness, tagsJson: String) = WorkFeatureEntity(
            subjectId = "1",
            tagCountsJson = tagsJson,
            staff = listOf("staff"),
            characters = listOf("character"),
            cv = listOf("cv"),
            bangumiScore = 8f,
            bangumiVotes = 100,
            eps = 12,
            durationMin = 24,
            platform = "TV",
            mediaType = MediaType.ANIME.name,
            titles = listOf("标题"),
            baseDataUpdatedAt = BASE_UPDATED_AT,
            extendedDataUpdatedAt = BASE_UPDATED_AT,
            completeness = completeness.name,
            updatedAt = BASE_UPDATED_AT,
        )

        private const val TAGS_JSON = "[{\"name\":\"治愈\",\"count\":3}]"
        private const val CHANGED_TAGS_JSON = "[{\"name\":\"治愈\",\"count\":4}]"
        private const val BASE_UPDATED_AT = 1_000L
        private const val NOW = 2_000L
    }
}
