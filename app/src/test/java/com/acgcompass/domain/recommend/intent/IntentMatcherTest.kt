package com.acgcompass.domain.recommend.intent

import com.acgcompass.domain.taste.TagCount
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * 「意图匹配 VNext」回归用例。作品标签取自 Bangumi 真实数据（Bangumi15M 条目元数据，2023-05 快照），
 * 不是为了让断言通过而编的——这样测试守护的是「真实标签分布下的行为」。
 */
class IntentMatcherTest : StringSpec({

    fun fit(tags: List<String>, w: TagRepresentativeness) = IntentMatcher.match(tags, w)!!

    "只选「催泪」：长篇热血番不算催泪番，哪怕它被人标过催泪" {
        // 真实标签里《航海王》前 24 个根本没有「催泪」；这里额外补一个排在末尾的「催泪」模拟「有人标过」。
        val withTag = TagRepresentativeness(onePieceTags + TagCount("催泪", 40), episodes = 1000)
        for (rep in listOf(onePiece, withTag)) {
            val m = fit(listOf("催泪"), rep)
            m.fit shouldBeLessThan 0.30
            (m.scoreCap!! <= 60) shouldBe true
            m.explain.missingImportantTags shouldBe listOf("催泪")
        }
    }

    "只选「催泪」：催泪是核心气质的作品契合度高且不封顶" {
        for (rep in listOf(clannadAs, anohana, yourLie)) {
            val m = fit(listOf("催泪"), rep)
            m.fit shouldBeGreaterThan 0.75
            m.scoreCap shouldBe null
        }
        // 《紫罗兰永恒花园》的「催泪」标注人数不多但确实在列：契合度应明显高于《航海王》。
        fit(listOf("催泪"), violet).fit shouldBeGreaterThan 0.5
    }

    "选「校园 + 恋爱 + 日常 + 搞笑」：只沾到搞笑的超能力战斗番被封顶" {
        val m = fit(listOf("校园", "恋爱", "日常", "搞笑"), mobPsycho)
        m.fit shouldBeLessThan 0.45
        m.scoreCap shouldBe 55
        m.explain.matchedStrongTags shouldBe listOf("搞笑")
        m.explain.missingImportantTags.contains("恋爱") shouldBe true
        m.dominantConflict shouldBeGreaterThan 0.0
        m.explain.dominantConflicts shouldNotBe emptyList<String>()
    }

    "选「校园 + 恋爱 + 日常 + 搞笑」：校园恋爱日常喜剧命中组合模板" {
        val m = fit(listOf("校园", "恋爱", "日常", "搞笑"), sakurasou)
        m.fit shouldBeGreaterThan 0.75
        m.scoreCap shouldBe null
        m.explain.matchedCombos shouldBe listOf("校园恋爱日常喜剧")
        m.explain.missingImportantTags shouldBe emptyList()
    }

    "选「校园 + 搞笑」：校园搞笑番契合度高；再加一个标签里看不出的诉求时会下降但不清零" {
        fit(listOf("校园", "搞笑"), bakaTest).fit shouldBeGreaterThan 0.9
        val withUnknown = fit(listOf("校园", "搞笑", "设定有趣"), bakaTest)
        withUnknown.fit shouldBeGreaterThan 0.3
        withUnknown.fit shouldBeLessThan 0.7
    }

    "多标签是 soft-AND：全中 > 只沾到一个 > 都没有" {
        val all = fit(listOf("校园", "搞笑"), bakaTest).fit
        val one = fit(listOf("校园", "搞笑"), mobPsycho).fit
        val none = fit(listOf("校园", "恋爱"), onePiece).fit
        all shouldBeGreaterThan one
        one shouldBeGreaterThan none
        none shouldBe 0.0
    }

    "近义标签算命中：选「恋爱」时「爱情」标签有效；字典外的标签只认自身" {
        val loveOnly = TagRepresentativeness(listOf(TagCount("爱情", 500), TagCount("TV", 300)))
        fit(listOf("恋爱"), loveOnly).fit shouldBeGreaterThan 0.7
        IntentTagDictionary.bundleFor("爱情").id shouldBe "恋爱"
        IntentTagDictionary.bundleFor("钓鱼").requiredTags shouldBe setOf("钓鱼")
    }

    "没有标注人数时退回按标签顺序判断" {
        val ordered = TagRepresentativeness(listOf("校园", "恋爱", "搞笑", "日常", "TV", "2012").map { TagCount(it, 0) })
        ordered.of("校园") shouldBeGreaterThan ordered.of("日常")
        fit(listOf("校园", "恋爱"), ordered).fit shouldBeGreaterThan 0.7
    }

    "长篇稀释只作用于情绪 / 基调类且排名靠后的标签" {
        val tags = (1..12).map { TagCount("题材$it", 1000 - it * 10) } + TagCount("催泪", 300) + TagCount("校园", 290)
        val short = TagRepresentativeness(tags, episodes = 12)
        val long = TagRepresentativeness(tags, episodes = 120)
        long.of("催泪", IntentCategory.EMOTION) shouldBeLessThan short.of("催泪", IntentCategory.EMOTION)
        long.of("校园", IntentCategory.SETTING) shouldBe short.of("校园", IntentCategory.SETTING)
        long.of("题材1", IntentCategory.EMOTION) shouldBe short.of("题材1", IntentCategory.EMOTION)
    }

    "未选标签时不做意图匹配" {
        IntentMatcher.match(emptyList(), sakurasou) shouldBe null
    }
})

private fun tags(vararg pairs: Pair<String, Int>): List<TagCount> = pairs.map { TagCount(it.first, it.second) }

private fun work(episodes: Int, vararg pairs: Pair<String, Int>) = TagRepresentativeness(tags(*pairs), episodes)

private val onePieceTags =
    tags(
        "ONEPIECE" to 3577, "海贼王" to 3156, "热血" to 2195, "尾田榮一郎" to 1516,
        "梦想" to 1074, "JUMP" to 1038, "路飞" to 817, "民工" to 797,
        "热血系" to 788, "集英社" to 520, "超连载" to 413, "TV" to 407,
        "OP" to 390, "海贼" to 282, "1999" to 162, "漫画改" to 106,
        "動畫" to 96, "战斗" to 78, "东映" to 68, "漫改" to 68,
        "冒险" to 46, "1999年10月" to 34, "搞笑" to 33, "童年" to 32,
    )
private val onePiece = TagRepresentativeness(onePieceTags, episodes = 1000)

private val mobPsycho =
    work(
        12,
        "BONES" to 2394, "漫画改" to 1299, "2016年7月" to 1268, "搞笑" to 1129,
        "TV" to 1039, "热血" to 950, "ONE" to 890, "灵能百分百" to 850,
        "漫改" to 694, "2016" to 587, "战斗" to 561, "立川让" to 323,
        "川井宪次" to 227, "樱井孝宏" to 174, "龟田祥伦" to 114, "超能力" to 113,
        "奇幻" to 100, "立川譲" to 66, "骨头社" to 30, "2016年" to 27,
        "校园" to 27, "细谷佳正" to 27, "作画" to 24, "治愈" to 22,
    )

private val sakurasou =
    work(
        24,
        "J.C.STAFF" to 2291, "樱花庄的宠物女孩" to 1862, "轻小说改" to 1696, "治愈" to 1428,
        "校园" to 1339, "2012年10月" to 1263, "恋爱" to 880, "TV" to 874,
        "茅野爱衣" to 759, "励志" to 606, "2012" to 479, "松冈祯丞" to 432,
        "冈田麿里" to 363, "轻小说" to 248, "轻改" to 222, "青春" to 140,
        "日常" to 92, "鸭志田一" to 60, "小说改" to 60, "2012十月番" to 51,
        "石塚敦子" to 47, "后宫" to 45, "搞笑" to 45, "岡田麿里" to 35,
    )

private val bakaTest =
    work(
        13,
        "搞笑" to 1786, "笨蛋测验召唤兽" to 1418, "轻小说改" to 1191, "秀吉" to 948,
        "校园" to 863, "2010年1月" to 860, "大沼心" to 726, "TV" to 650,
        "性别秀吉" to 640, "2010" to 416, "伪娘" to 410, "SILVERLINK." to 345,
        "萌" to 318, "笨蛋" to 241, "废柴男主" to 190, "轻改" to 140,
        "轻小说" to 121, "后宫" to 96, "恋爱" to 75, "日常" to 65,
        "小说改" to 46, "加滕英美里" to 34, "SILVERLINK" to 33, "2010年" to 31,
    )

private val yourLie =
    work(
        22,
        "恋爱" to 2807, "A-1Pictures" to 2669, "音乐" to 2268, "四月は君の嘘" to 1707,
        "治愈" to 1613, "2014年10月" to 1519, "青春" to 1353, "漫画改" to 1291,
        "TV" to 1019, "催泪" to 948, "2014" to 636, "漫改" to 502,
        "花江夏樹" to 364, "noitaminA" to 287, "校园" to 264, "逢坂良太" to 111,
        "梶裕貴" to 72, "石黑恭平" to 66, "致郁" to 65, "横山克" to 64,
        "种田梨沙" to 48, "四月是你的谎言" to 42, "2014年" to 36, "神作" to 35,
    )

private val clannadAs =
    work(
        24,
        "Clannad" to 6044, "京阿尼" to 5246, "key" to 4697, "催泪" to 4169,
        "麻枝准" to 3035, "神作" to 2897, "治愈" to 2700, "人生" to 2699,
        "~After-Story~" to 1415, "治愈系" to 1086, "TV" to 1003, "催淚" to 707,
        "galgame" to 699, "2008年10月" to 690, "爱情" to 617, "2008" to 464,
        "GAL改" to 415, "游戏改" to 319, "京都动画" to 208, "石原立也" to 208,
        "恋爱" to 148, "亲情" to 102, "京都" to 96, "校园" to 95,
    )

private val anohana =
    work(
        11,
        "治愈" to 4370, "催泪" to 3817, "あの日見た花の名前を僕達はまだ知らない" to 2376, "友情" to 2288,
        "2011年4月" to 1634, "A-1Pictures" to 1341, "TV" to 1134, "原创" to 918,
        "伤感" to 886, "あの花" to 634, "noitaminA" to 613, "2011" to 578,
        "A-1" to 456, "长井龙雪" to 261, "櫻井孝宏" to 214, "四月番" to 128,
        "冈田麿里" to 111, "入野自由" to 101, "岡田麿里" to 99, "青春" to 93,
        "恋爱" to 80, "長井龍雪" to 74, "未闻花名" to 70, "冈田磨里" to 58,
    )

private val violet =
    work(
        13,
        "京阿尼" to 3675, "轻小说改" to 1774, "2018年1月" to 1751, "紫罗兰永恒花园" to 1612,
        "京都动画" to 1181, "TV" to 1163, "石立太一" to 866, "治愈" to 858,
        "2018" to 706, "轻改" to 628, "奇幻" to 572, "京都アニメーション" to 281,
        "文库唯一大赏" to 200, "催泪" to 116, "小说改" to 105, "日常" to 64,
        "吉田玲子" to 58, "京都" to 46, "恋爱" to 45, "石川由依" to 40,
        "2018年" to 36, "单元剧" to 30, "日本" to 27, "战争" to 24,
    )
