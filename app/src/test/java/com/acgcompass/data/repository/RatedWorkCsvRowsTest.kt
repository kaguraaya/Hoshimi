package com.acgcompass.data.repository

import com.acgcompass.data.backup.BackupEnvelope
import com.acgcompass.data.backup.BackupTag
import com.acgcompass.data.backup.BackupUserCollection
import com.acgcompass.data.backup.BackupWork
import com.acgcompass.data.backup.BackupWorkFeature
import com.acgcompass.data.backup.BackupWorkTag
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class RatedWorkCsvRowsTest : StringSpec({
    "rated work rows merge cached community tags without using local timestamps" {
        val envelope = BackupEnvelope(
            works = listOf(
                BackupWork(
                    id = "42",
                    canonicalTitle = "测试作品",
                    mediaType = "ANIME",
                    status = "FINISHED",
                    primarySource = "BANGUMI",
                    createdAt = 1L,
                    updatedAt = 2L,
                ),
            ),
            tags = listOf(BackupTag(id = "tag:科幻", category = "TOPIC", name = "科幻")),
            workTags = listOf(BackupWorkTag(workId = "42", tagId = "tag:科幻")),
            workFeatures = listOf(
                BackupWorkFeature(
                    subjectId = "42",
                    tagCountsJson = """[{"name":"科幻","count":20},{"name":"时间旅行","count":8}]""",
                    bangumiScore = 8.5f,
                    bangumiVotes = 100,
                    eps = 24,
                    durationMin = 24,
                    updatedAt = 3L,
                ),
            ),
            userCollections = listOf(
                BackupUserCollection(
                    id = "BANGUMI:42",
                    source = "BANGUMI",
                    sourceItemId = "42",
                    localWorkId = "42",
                    rating = 9,
                    updatedAt = 999L,
                    syncedAt = 1000L,
                    sourceUpdatedAt = "2026-08-09T21:15:30+08:00",
                ),
                BackupUserCollection(
                    id = "BANGUMI:43",
                    source = "BANGUMI",
                    sourceItemId = "43",
                    localWorkId = "43",
                    rating = null,
                    updatedAt = 999L,
                    syncedAt = 1000L,
                ),
            ),
        )

        val rows = envelope.toRatedWorkCsvRows()

        rows.size shouldBe 1
        rows.single().workId shouldBe "42"
        rows.single().title shouldBe "测试作品"
        rows.single().rating shouldBe 9
        rows.single().sourceUpdatedAt shouldBe "2026-08-09T21:15:30+08:00"
        rows.single().tags.toSet() shouldBe setOf("科幻", "时间旅行")
    }
})
