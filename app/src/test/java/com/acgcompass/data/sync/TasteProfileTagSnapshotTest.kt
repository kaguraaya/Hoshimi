package com.acgcompass.data.sync

import com.acgcompass.domain.taste.TagCount
import com.acgcompass.domain.taste.WorkFeature
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly

class TasteProfileTagSnapshotTest : StringSpec({
    "local taste input contains personal and complete work feature tags" {
        val feature = WorkFeature(
            subjectId = "1",
            tagCounts = listOf(TagCount("science fiction", 100), TagCount("time travel", 80)),
        )

        mergeTasteInputTags(listOf("custom", " science fiction "), feature) shouldContainExactly
            listOf("custom", "science fiction", "time travel")
    }
})
