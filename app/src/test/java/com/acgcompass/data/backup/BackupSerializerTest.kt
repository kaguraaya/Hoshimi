package com.acgcompass.data.backup

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * 备份序列化器与合并器单元测试。
 *
 * 覆盖默认零凭据、序列化 round-trip、合并不覆盖和跨版本设置恢复。
 * 测试对象均为纯函数，无 Android / DAO 依赖。
 */
class BackupSerializerTest : StringSpec({

    @Suppress("LongMethod", "ktlint:standard:function-signature")
    fun sampleEnvelope(): BackupEnvelope =
        BackupEnvelope(
            schemaVersion = BackupEnvelope.CURRENT_SCHEMA_VERSION,
            exportedAt = 1_730_000_000_000L,
            appVersion = "0.1.0",
            works = listOf(
                BackupWork(
                    id = "w1",
                    canonicalTitle = "作品一",
                    titleJa = "さくひん",
                    aliases = listOf("别名A", "别名B"),
                    mediaType = "ANIME",
                    year = 2021,
                    status = "FINISHED",
                    primarySource = "BANGUMI",
                    createdAt = 1000,
                    updatedAt = 2000,
                    titleCn = "作品一中文名",
                    summary = "简介",
                    airDate = "2021-04-01",
                ),
            ),
            backlog = listOf(
                BackupBacklogItem(
                    workId = "w1",
                    priority = "HIGH",
                    moodTags = listOf("治愈"),
                    riskTags = emptyList(),
                    note = "记得看",
                    addedAt = 1500,
                    dustDays = 3,
                    inDustMuseum = true,
                    prevStatus = "在看",
                ),
            ),
            ratings = listOf(
                BackupRating("r1", "w1", "BANGUMI", 8.5f, 1200, 42, 1800, missing = false),
            ),
            tags = listOf(BackupTag("t1", "MOOD", "治愈")),
            workTags = listOf(BackupWorkTag("w1", "t1")),
            userCollections = listOf(
                BackupUserCollection(
                    id = "c1",
                    source = "BANGUMI",
                    sourceItemId = "1",
                    localWorkId = "w1",
                    status = "看过",
                    rating = 8,
                    comment = "喜欢",
                    tags = listOf("科幻"),
                    updatedAt = 2_000,
                    syncedAt = 2_100,
                ),
            ),
            workFeatures = listOf(
                BackupWorkFeature(
                    subjectId = "1",
                    tagCountsJson = "{\"科幻\":10}",
                    bangumiScore = 8.5f,
                    bangumiVotes = 1_200,
                    eps = 24,
                    durationMin = 24,
                    titles = listOf("作品一"),
                    completeness = "BASIC",
                    updatedAt = 2_000,
                    coverUrl = "https://example.com/cover.jpg",
                    airDate = "2021-04-01",
                ),
            ),
            trashItems = listOf(
                BackupTrashItem(
                    id = "COLLECTION:w2",
                    kind = "COLLECTION",
                    workId = "w2",
                    batchId = "b1",
                    title = "移除的作品",
                    payloadJson = null,
                    reason = "USER",
                    removedAt = 1_900,
                    purged = true,
                ),
            ),
            tagDimensions = listOf(
                BackupTagDimension(
                    tag = "科幻",
                    dimension = "GENRE",
                    source = "AI",
                    confidence = 0.9f,
                    updatedAt = 2_000,
                    classifierVersion = 2,
                ),
            ),
        )

    "default export excludes credentials " {
        val json = BackupSerializer.serialize(sampleEnvelope())

        json shouldContain "\"includesCredentials\": false"
        json shouldContain "\"credentials\": null"
    }

    "serialize/deserialize round-trips business data " {
        val original = sampleEnvelope()

        val restored = BackupSerializer.deserialize(BackupSerializer.serialize(original))

        restored shouldBe original
    }

    "merge adds rows missing locally " {
        val local = sampleEnvelope().copy(
            works = emptyList(),
            backlog = emptyList(),
            ratings = emptyList(),
            tags = emptyList(),
            workTags = emptyList(),
            userCollections = emptyList(),
            workFeatures = emptyList(),
            tagDimensions = emptyList(),
        )
        val incoming = sampleEnvelope()

        val result = BackupMerger.merge(local = local, incoming = incoming)

        val expectedAdded = listOf(
            incoming.works,
            incoming.backlog,
            incoming.ratings,
            incoming.tags,
            incoming.workTags,
            incoming.userCollections,
            incoming.workFeatures,
            incoming.tagDimensions,
        ).sumOf { it.size }
        result.addedCount shouldBe expectedAdded
        result.merged.works.map { it.id } shouldBe listOf("w1")
        result.updatedCount shouldBe 0
    }

    "merge keeps newer local work and does not overwrite with older incoming " {
        val local = sampleEnvelope() // w1.updatedAt = 2000
        val olderIncoming = sampleEnvelope().copy(
            works = listOf(
                sampleEnvelope().works.first().copy(canonicalTitle = "旧标题", updatedAt = 1000),
            ),
        )

        val result = BackupMerger.merge(local = local, incoming = olderIncoming)

        // 本地较新，导入较旧 → 保留本地，不覆盖。
        result.merged.works.first().canonicalTitle shouldBe "作品一"
        result.updatedCount shouldBe 0
        result.conflicts.any {
            it.table == "works" && it.key == "w1" &&
                it.resolution == BackupMerger.Resolution.KEPT_LOCAL
        } shouldBe true
    }

    "merge adopts strictly newer incoming work (default keep newer updatedAt)" {
        val local = sampleEnvelope() // w1.updatedAt = 2000
        val newerIncoming = sampleEnvelope().copy(
            works = listOf(
                sampleEnvelope().works.first().copy(canonicalTitle = "新标题", updatedAt = 3000),
            ),
        )

        val result = BackupMerger.merge(local = local, incoming = newerIncoming)

        result.merged.works.first().canonicalTitle shouldBe "新标题"
        result.updatedCount shouldBe 1
        result.conflicts.any {
            it.table == "works" && it.resolution == BackupMerger.Resolution.USED_INCOMING
        } shouldBe true
    }

    "importing a backup into itself counts every conflict but keeps only a few samples" {
        val base = sampleEnvelope().works.first()
        val many = BackupEnvelope(
            schemaVersion = BackupEnvelope.CURRENT_SCHEMA_VERSION,
            exportedAt = 0L,
            appVersion = "test",
            works = (1..BIG_TABLE).map { base.copy(id = "w$it") },
        )

        val result = BackupMerger.merge(local = many, incoming = many)

        result.conflictCount shouldBe BIG_TABLE
        result.keptLocalCount shouldBe BIG_TABLE
        result.conflicts.size shouldBe BackupMerger.MAX_CONFLICT_SAMPLES
    }

    "merged envelope never carries credentials ()" {
        val local = sampleEnvelope()
        val incoming = sampleEnvelope()

        val result = BackupMerger.merge(local = local, incoming = incoming)

        result.merged.credentials shouldBe null
        result.merged.includesCredentials shouldBe false
        BackupSerializer.serialize(result.merged) shouldNotContain "\"credentials\": {"
    }

    "schema 2 restores non-sensitive settings" {
        val local = sampleEnvelope().copy(settings = BackupSettings(dynamicColor = false, autoCacheMaxCount = 100))
        val incoming = sampleEnvelope().copy(settings = BackupSettings(dynamicColor = true, autoCacheMaxCount = 3_000))

        val result = BackupMerger.merge(local = local, incoming = incoming)

        result.merged.settings shouldBe incoming.settings
    }

    "schema 1 preserves local settings for backward compatibility" {
        val local = sampleEnvelope().copy(settings = BackupSettings(dynamicColor = false, autoCacheMaxCount = 100))
        val incoming = sampleEnvelope().copy(
            schemaVersion = 1,
            settings = BackupSettings(dynamicColor = true, autoCacheMaxCount = 3_000),
        )

        val result = BackupMerger.merge(local = local, incoming = incoming)

        result.merged.settings shouldBe local.settings
    }

    "older backups without the newer fields still import" {
        val json = """{"schemaVersion":2,"works":[{"id":"w9","canonicalTitle":"旧作","mediaType":"ANIME",
            "status":"FINISHED","primarySource":"BANGUMI","createdAt":1,"updatedAt":2}],
            "backlog":[{"workId":"w9","priority":"HIGH","addedAt":1,"dustDays":0,"inDustMuseum":false}]}"""

        val restored = BackupSerializer.deserialize(json)

        restored.works.single().titleCn shouldBe null
        restored.backlog.single().prevStatus shouldBe null
        restored.trashItems shouldBe emptyList()
    }

    "newer incoming work from an older backup keeps local titleCn, summary and airDate" {
        val local = sampleEnvelope()
        val olderFormat = sampleEnvelope().works.first().copy(
            canonicalTitle = "新标题",
            updatedAt = 3_000,
            titleCn = null,
            summary = null,
            airDate = null,
        )
        val feature = sampleEnvelope().workFeatures.first().copy(updatedAt = 3_000, coverUrl = null, airDate = null)

        val merged = BackupMerger.merge(
            local = local,
            incoming = sampleEnvelope().copy(works = listOf(olderFormat), workFeatures = listOf(feature)),
        ).merged

        merged.works.first().canonicalTitle shouldBe "新标题"
        merged.works.first().titleCn shouldBe "作品一中文名"
        merged.works.first().summary shouldBe "简介"
        merged.works.first().airDate shouldBe "2021-04-01"
        merged.workFeatures.first().coverUrl shouldBe "https://example.com/cover.jpg"
    }

    "trash items are merged by id and imported when missing locally" {
        val local = sampleEnvelope().copy(trashItems = emptyList())

        val result = BackupMerger.merge(local = local, incoming = sampleEnvelope())

        result.merged.trashItems.map { it.id } shouldBe listOf("COLLECTION:w2")
        result.merged.trashItems.single().purged shouldBe true
    }

    "entity mappings keep every backed-up field" {
        val work = sampleEnvelope().works.first()
        work.toEntity().toBackup() shouldBe work
        val feature = sampleEnvelope().workFeatures.first()
        feature.toEntity().toBackup() shouldBe feature
        val backlog = sampleEnvelope().backlog.first()
        backlog.toEntity().toBackup() shouldBe backlog
        val trash = sampleEnvelope().trashItems.first()
        trash.toEntity().toBackup() shouldBe trash
    }
})

private const val BIG_TABLE = 500
