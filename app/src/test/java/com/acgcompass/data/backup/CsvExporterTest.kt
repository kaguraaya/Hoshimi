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

        csv shouldBe "作品ID,作品名,来源,个人评分,状态,短评,同步时间\n" +
            "1,,BANGUMI,9,COMPLETED,\"第一行,\"\"很好\"\"\n第二行\",20"
    }

    "personal ratings export contains only the header when no personal record exists" {
        CsvExporter.exportPersonalRatings(emptyList()) shouldBe
            "作品ID,作品名,来源,个人评分,状态,短评,同步时间"
    }

    "rated works export uses source time and lists every community tag" {
        val csv = CsvExporter.exportRatedWorks(
            listOf(
                RatedWorkCsvRow(
                    workId = "123",
                    title = "测试,作品",
                    rating = 9,
                    sourceUpdatedAt = "2026-08-09T21:15:30+08:00",
                    tags = listOf("科幻", "时间旅行", "科幻"),
                ),
            ),
        )

        csv shouldBe "作品ID,作品名,个人评分,评分时间（来源记录）,标签名\n" +
            "123,\"测试,作品\",9,2026-08-09T21:15:30+08:00,时间旅行、科幻"
    }

    "rated works export leaves unavailable source time empty" {
        CsvExporter.exportRatedWorks(
            listOf(
                RatedWorkCsvRow(
                    workId = "456",
                    title = "无来源时间",
                    rating = 8,
                    sourceUpdatedAt = null,
                    tags = emptyList(),
                ),
            ),
        ) shouldBe "作品ID,作品名,个人评分,评分时间（来源记录）,标签名\n456,无来源时间,8,,"
    }

    "personal ratings export includes the work title" {
        val collection = BackupUserCollection(
            id = "BANGUMI:7",
            source = "BANGUMI",
            sourceItemId = "7",
            localWorkId = "7",
            rating = 10,
            updatedAt = 1L,
            syncedAt = 2L,
        )

        CsvExporter.exportPersonalRatings(listOf(collection), mapOf("7" to "作品名")) shouldBe
            "作品ID,作品名,来源,个人评分,状态,短评,同步时间\n7,作品名,BANGUMI,10,,,2"
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
