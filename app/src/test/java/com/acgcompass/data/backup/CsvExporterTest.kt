package com.acgcompass.data.backup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class CsvExporterTest : StringSpec({
    "personal ratings export preserves status and escapes review text" {
        val csv = CsvExporter.exportPersonalRatings(
            listOf(
                BackupUserCollection(
                    id = "BANGUMI:1",
                    source = "BANGUMI",
                    sourceItemId = "1",
                    localWorkId = "1",
                    status = "COMPLETED",
                    rating = 9,
                    comment = "第一行,\"很好\"\n第二行",
                    updatedAt = 10L,
                    syncedAt = 20L,
                ),
            ),
        )

        csv shouldBe "作品ID,来源,个人评分,状态,短评,同步时间\n" +
            "1,BANGUMI,9,COMPLETED,\"第一行,\"\"很好\"\"\n第二行\",20"
    }

    "personal ratings export contains only the header when no personal record exists" {
        CsvExporter.exportPersonalRatings(emptyList()) shouldBe
            "作品ID,来源,个人评分,状态,短评,同步时间"
    }

    "time machine export includes the current collection baseline without change logs" {
        val csv = CsvExporter.exportTimeMachine(
            changeLogs = emptyList(),
            snapshots = emptyList(),
            collections = listOf(
                BackupUserCollection(
                    id = "BANGUMI:1",
                    source = "BANGUMI",
                    sourceItemId = "1",
                    localWorkId = "1",
                    status = "看过",
                    rating = 9,
                    progress = 12,
                    updatedAt = 30L,
                    syncedAt = 40L,
                ),
            ),
            titlesById = mapOf("1" to "测试作品"),
        )

        csv.lineSequence().count() shouldBe 2
        csv.contains("当前基线,30,1,测试作品,收藏状态,当前记录,,状态=看过；评分=9；进度=12") shouldBe true
    }

    "taste profile export contains classification and frequency evidence" {
        val csv = CsvExporter.exportTasteProfile(
            listOf(
                TasteTagCsvRow(
                    rawTag = "科幻",
                    normalizedTag = "科幻",
                    dimension = "题材",
                    source = "LOCAL_RULE",
                    confidence = 1f,
                    classifierVersion = 0,
                    status = "CLASSIFIED",
                    workCount = 8,
                    communityCount = 320,
                    highScoreCount = 5,
                    lowScoreCount = 1,
                    inPersonalSnapshot = true,
                    updatedAt = 50L,
                ),
            ),
        )

        csv.lineSequence().count() shouldBe 2
        csv.contains("科幻,科幻,题材,LOCAL_RULE,1.0,0,CLASSIFIED,8,320,5,1,是,50") shouldBe true
    }
})
