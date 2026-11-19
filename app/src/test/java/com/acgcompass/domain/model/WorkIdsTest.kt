package com.acgcompass.domain.model

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WorkIdsTest : StringSpec({
    "Bangumi 和 VNDB 用原编号，其它平台加来源前缀" {
        WorkIds.local(SourceId.BANGUMI, "21") shouldBe "21"
        WorkIds.local(SourceId.VNDB, "v17") shouldBe "v17"
        WorkIds.local(SourceId.ANILIST, "21") shouldBe "ANILIST:21"
        WorkIds.local(SourceId.TENRAI, "21") shouldBe "TENRAI:21"
        WorkIds.local(SourceId.MAL, "21") shouldBe "MAL:21"
        WorkIds.local(SourceId.ANILIST, "ANILIST:21") shouldBe "ANILIST:21"
    }

    "同号的 Bangumi 作品和 AniList 作品本地 id 不同" {
        (WorkIds.local(SourceId.BANGUMI, "21") == WorkIds.local(SourceId.ANILIST, "21")) shouldBe false
    }

    "取回平台原始编号" {
        WorkIds.sourceItemId("ANILIST:21", SourceId.ANILIST) shouldBe "21"
        WorkIds.sourceItemId("21", SourceId.ANILIST) shouldBe "21"
        WorkIds.sourceItemId("21", SourceId.BANGUMI) shouldBe "21"
        WorkIds.sourceItemId("TENRAI:5", SourceId.TENRAI) shouldBe "5"
    }
})
