package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** 为你排行里同一系列只留一部：剧场版、续作、副标题折叠到同一个键，不同作品不误折。 */
class RankFoldKeyTest : StringSpec({
    "movies, sequels and subtitles of one series share a key" {
        rankFoldKey("剧场版 魔法少女小圆 [前篇] 起始的物语") shouldBe rankFoldKey("魔法少女小圆")
        rankFoldKey("剧场版 魔法少女小圆 [新篇] 叛逆的物语") shouldBe rankFoldKey("魔法少女小圆")
        rankFoldKey("3月的狮子 第二季") shouldBe rankFoldKey("3月的狮子")
        rankFoldKey("3月のライオン 第2シリーズ") shouldBe rankFoldKey("3月のライオン")
        rankFoldKey("響け！ユーフォニアム2") shouldBe rankFoldKey("響け！ユーフォニアム")
        rankFoldKey("攻殻機動隊 S.A.C. 2nd GIG") shouldBe rankFoldKey("攻殻機動隊 STAND ALONE COMPLEX")
        rankFoldKey("劇場版 魔法少女まどか☆マギカ [新編] 叛逆の物語") shouldBe rankFoldKey("魔法少女まどか☆マギカ")
    }

    "different works keep different keys" {
        rankFoldKey("AIR") shouldNotBe rankFoldKey("CLANNAD")
        rankFoldKey("千年女优") shouldNotBe rankFoldKey("千与千寻")
        rankFoldKey("Re:ゼロから始める異世界生活") shouldNotBe rankFoldKey("Re:CREATORS")
        rankFoldKey("Little Busters!") shouldNotBe rankFoldKey("Little Witch Academia")
        rankFoldKey("86―エイティシックス―") shouldNotBe rankFoldKey("89")
    }

    "only the highest-ranked work of a series stays" {
        fun item(id: String, title: String) =
            TasteRankItem(id, title, null, null, WorkFormat.TV, true, 90, null, null, null)
        val folded = listOf(
            item("1", "剧场版 魔法少女小圆 [后篇] 永远的物语"),
            item("2", "剧场版 魔法少女小圆 [前篇] 起始的物语"),
            item("3", "3月的狮子 第二季"),
            item("4", "AIR"),
            item("5", "3月的狮子"),
        ).foldFranchises()
        folded.map { it.subjectId } shouldBe listOf("1", "3", "4")
    }
})
