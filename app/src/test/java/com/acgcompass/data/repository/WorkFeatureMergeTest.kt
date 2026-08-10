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

    "changed embedded tags require base verification without discarding extended names" {
        val existing = entity(completeness = WorkFeatureCompleteness.EXTENDED, tagsJson = TAGS_JSON)

        val merged = mergeLightweightFeature(feature(), existing, CHANGED_TAGS_JSON, NOW)

        merged.completeness shouldBe WorkFeatureCompleteness.EMBEDDED.name
        merged.tagCountsJson shouldBe CHANGED_TAGS_JSON
        merged.baseDataUpdatedAt shouldBe NOW
        merged.staff shouldBe listOf("staff")
        merged.characters shouldBe listOf("character")
        merged.cv shouldBe listOf("cv")
    }
}) {
    companion object {
        private fun feature() = WorkFeature(
            subjectId = "1",
            tagCounts = listOf(TagCount("治愈", 3)),
            bangumiScore = 8f,
            bangumiVotes = 100,
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
