package com.acgcompass.feature.home

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Tag
import com.acgcompass.domain.model.TagCategory
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class HomeMoodSuggestionsTest : StringSpec({

    fun work(id: String, vararg tags: String) = Work(
        id = id,
        titles = Titles(canonical = id),
        mediaType = MediaType.ANIME,
        primarySource = SourceId.BANGUMI,
        tags = tags.map { Tag(TagCategory.CONTENT, it) },
    )

    fun entry(id: String, status: String, rating: Int?, updatedAt: Long) = MoodSignal(id, status, rating, updatedAt)

    "few records fall back to the default types" {
        buildMoodSuggestions(emptyList(), emptyMap(), Random(1)) shouldBe HomeMood.DEFAULTS
    }

    "recent liked works drive the suggested tags; dropped works do not" {
        val works = listOf(
            work("a", "搞笑", "校园", "日常"),
            work("b", "恋爱", "校园", "治愈"),
            work("c", "热血", "战斗", "奇幻"),
            work("d", "悬疑", "推理"),
        ).associateBy { it.id }
        val collections = listOf(
            entry("a", "看过", 8, 4),
            entry("b", "在看", null, 3),
            entry("c", "看过", 7, 2),
            entry("d", "抛弃", 3, 1),
        )
        val labels = buildMoodSuggestions(collections, works, Random(7), count = 8).map { it.label }
        labels shouldContain "校园"
        labels shouldNotContain "推理"
        labels.size shouldBe labels.toSet().size
    }
})
