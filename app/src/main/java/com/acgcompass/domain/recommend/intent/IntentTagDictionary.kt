package com.acgcompass.domain.recommend.intent

import com.acgcompass.domain.recommend.intent.IntentCategory.EMOTION
import com.acgcompass.domain.recommend.intent.IntentCategory.RELATION
import com.acgcompass.domain.recommend.intent.IntentCategory.SETTING
import com.acgcompass.domain.recommend.intent.IntentCategory.STRUCTURE
import com.acgcompass.domain.recommend.intent.IntentCategory.TONE
import com.acgcompass.domain.recommend.intent.IntentCategory.TOPIC

/**
 * UI 标签 → 意图包的字典（纯数据）。
 *
 * 只收录「一个词背后有明确近义 / 冲突关系」的常用标签；字典外的标签走 [bundleFor] 的默认包
 * （只认标签本身，不扩展近义，也不算关键意图），行为与直接比对标签一致。
 */
@Suppress("MagicNumber") // 阈值与惩罚值即字典内容本身。
object IntentTagDictionary {

    private val HEAVY_ACTION = setOf("战斗", "热血", "超能力", "机战", "大逃杀", "异能")
    private val DARK = setOf("致郁", "猎奇", "恐怖", "惊悚", "血腥", "黑暗")
    private val LIGHT_COMEDY = setOf("搞笑", "无厘头", "沙雕", "子供向")

    private val BUNDLES: List<IntentTagBundle> = listOf(
        IntentTagBundle(
            id = "催泪", category = EMOTION,
            requiredTags = setOf("催泪"),
            synonymTags = setOf("感动", "泪腺崩坏", "虐心", "哭", "虐"),
            weakTags = setOf("治愈", "亲情", "友情", "成长", "励志"),
            conflictTags = LIGHT_COMEDY + setOf("冒险", "热血", "战斗"),
            representativeThreshold = 0.35, missingPenalty = 0.35, isKey = true,
        ),
        IntentTagBundle(
            id = "治愈",
            category = EMOTION,
            requiredTags = setOf("治愈"),
            synonymTags = setOf("治愈系", "温馨", "温暖", "空气系"),
            weakTags = setOf("日常", "萌", "轻松"),
            conflictTags = DARK + setOf("大逃杀"),
            missingPenalty = 0.30,
            isKey = true,
        ),
        IntentTagBundle(
            id = "致郁",
            category = EMOTION,
            requiredTags = setOf("致郁"),
            synonymTags = setOf("致郁系", "黑暗", "虐心", "胃痛", "压抑"),
            weakTags = setOf("悲剧", "猎奇"),
            conflictTags = setOf("治愈", "轻松", "搞笑", "子供向"),
            missingPenalty = 0.30,
            isKey = true,
        ),
        IntentTagBundle(
            id = "搞笑",
            category = TONE,
            requiredTags = setOf("搞笑"),
            synonymTags = setOf("喜剧", "吐槽", "沙雕", "无厘头", "欢乐", "爆笑"),
            weakTags = setOf("轻松", "日常"),
            conflictTags = DARK + setOf("沉重"),
            missingPenalty = 0.25,
        ),
        IntentTagBundle(
            id = "轻松",
            category = TONE,
            requiredTags = setOf("轻松"),
            synonymTags = setOf("欢乐", "休闲", "空气系", "下饭"),
            weakTags = setOf("日常", "搞笑", "治愈", "萌"),
            conflictTags = DARK + setOf("烧脑", "大逃杀"),
        ),
        IntentTagBundle(
            id = "日常",
            category = STRUCTURE,
            requiredTags = setOf("日常"),
            synonymTags = setOf("空气系", "生活", "日常系", "治愈日常"),
            weakTags = setOf("轻松", "萌", "社团"),
            conflictTags = HEAVY_ACTION + setOf("末世", "猎奇"),
            representativeThreshold = 0.28,
            missingPenalty = 0.25,
        ),
        IntentTagBundle(
            id = "校园",
            category = SETTING,
            requiredTags = setOf("校园", "学园"),
            synonymTags = setOf("高中", "学生", "青春校园", "社团"),
            weakTags = setOf("青春"),
            conflictTags = setOf("职场", "异世界", "末世"),
            missingPenalty = 0.25,
            isKey = true,
        ),
        IntentTagBundle(
            id = "恋爱", category = RELATION,
            requiredTags = setOf("恋爱"),
            synonymTags = setOf("爱情", "纯爱", "党争", "青春恋爱", "恋爱喜剧", "恋爱番"),
            weakTags = setOf("暧昧", "青梅竹马", "告白", "后宫"),
            conflictTags = setOf("硬核战斗"),
            representativeThreshold = 0.32, missingPenalty = 0.35, isKey = true,
        ),
        IntentTagBundle(
            id = "党争",
            category = RELATION,
            requiredTags = setOf("党争"),
            synonymTags = setOf("修罗场", "三角恋"),
            weakTags = setOf("恋爱", "后宫"),
            missingPenalty = 0.30,
            isKey = true,
        ),
        IntentTagBundle(
            id = "百合",
            category = RELATION,
            requiredTags = setOf("百合"),
            synonymTags = setOf("gl", "轻百合", "少女爱"),
            missingPenalty = 0.30,
            isKey = true,
        ),
        IntentTagBundle(
            id = "后宫",
            category = RELATION,
            requiredTags = setOf("后宫"),
            synonymTags = setOf("后宫向", "卖肉"),
            weakTags = setOf("恋爱"),
        ),
        IntentTagBundle(
            id = "悬疑",
            category = TOPIC,
            requiredTags = setOf("悬疑"),
            synonymTags = setOf("推理", "解谜", "烧脑", "神展开", "智斗"),
            weakTags = setOf("惊悚", "科幻"),
            conflictTags = setOf("日常", "空气系", "子供向"),
            missingPenalty = 0.30,
            isKey = true,
        ),
        IntentTagBundle(
            id = "推理",
            category = TOPIC,
            requiredTags = setOf("推理"),
            synonymTags = setOf("侦探", "解谜", "悬疑"),
            missingPenalty = 0.30,
            isKey = true,
        ),
        IntentTagBundle(
            id = "恐怖",
            category = TOPIC,
            requiredTags = setOf("恐怖"),
            synonymTags = setOf("惊悚", "猎奇", "灵异"),
            weakTags = setOf("悬疑", "血腥"),
            conflictTags = setOf("治愈", "日常", "子供向"),
            missingPenalty = 0.30,
            isKey = true,
        ),
        IntentTagBundle(
            id = "热血",
            category = TONE,
            requiredTags = setOf("热血"),
            synonymTags = setOf("燃", "少年漫", "王道"),
            weakTags = setOf("战斗", "冒险", "运动"),
            conflictTags = setOf("空气系"),
        ),
        IntentTagBundle(
            id = "战斗",
            category = TOPIC,
            requiredTags = setOf("战斗"),
            synonymTags = setOf("打斗", "动作", "异能", "超能力"),
            weakTags = setOf("热血", "机战", "冒险"),
            conflictTags = setOf("空气系", "日常"),
        ),
        IntentTagBundle(
            id = "冒险",
            category = TOPIC,
            requiredTags = setOf("冒险"),
            synonymTags = setOf("旅行", "公路片"),
            weakTags = setOf("奇幻", "异世界"),
        ),
        IntentTagBundle(
            id = "科幻",
            category = TOPIC,
            requiredTags = setOf("科幻"),
            synonymTags = setOf("sf", "赛博朋克", "太空", "机器人", "时间旅行"),
            weakTags = setOf("机战", "未来"),
            missingPenalty = 0.25,
            isKey = true,
        ),
        IntentTagBundle(
            id = "奇幻",
            category = SETTING,
            requiredTags = setOf("奇幻"),
            synonymTags = setOf("魔法", "幻想", "剑与魔法", "异世界"),
            weakTags = setOf("冒险"),
        ),
        IntentTagBundle(
            id = "异世界",
            category = SETTING,
            requiredTags = setOf("异世界"),
            synonymTags = setOf("穿越", "转生", "异世界转生"),
            weakTags = setOf("奇幻", "魔法"),
            missingPenalty = 0.25,
            isKey = true,
        ),
        IntentTagBundle(
            id = "机战",
            category = TOPIC,
            requiredTags = setOf("机战"),
            synonymTags = setOf("机器人", "萝卜", "机甲"),
            weakTags = setOf("科幻"),
            missingPenalty = 0.25,
            isKey = true,
        ),
        IntentTagBundle(
            id = "运动",
            category = TOPIC,
            requiredTags = setOf("运动"),
            synonymTags = setOf("体育", "竞技", "篮球", "排球", "足球", "棒球"),
            weakTags = setOf("热血", "青春"),
            missingPenalty = 0.25,
            isKey = true,
        ),
        IntentTagBundle(
            id = "音乐",
            category = TOPIC,
            requiredTags = setOf("音乐"),
            synonymTags = setOf("乐队", "偶像", "轻音", "演奏"),
            missingPenalty = 0.25,
            isKey = true,
        ),
        IntentTagBundle(
            id = "青春",
            category = TONE,
            requiredTags = setOf("青春"),
            synonymTags = setOf("青春群像", "青春校园"),
            weakTags = setOf("校园", "成长", "恋爱"),
        ),
        IntentTagBundle(
            id = "萌",
            category = TONE,
            requiredTags = setOf("萌"),
            synonymTags = setOf("萌系", "卖萌", "萌豚"),
            weakTags = setOf("日常", "萝莉"),
            conflictTags = DARK,
        ),
        IntentTagBundle(
            id = "美食",
            category = TOPIC,
            requiredTags = setOf("美食"),
            synonymTags = setOf("料理", "吃货"),
        ),
        IntentTagBundle(
            id = "历史",
            category = SETTING,
            requiredTags = setOf("历史"),
            synonymTags = setOf("战国", "古风", "时代剧"),
        ),
    )

    private val BY_ID: Map<String, IntentTagBundle> = BUNDLES.associateBy { it.id }

    /** 标签 → 所属意图 id（required / synonym 命中即归入），供把作品标签折算成「主气质」。 */
    private val ALIAS_TO_ID: Map<String, String> = buildMap {
        for (b in BUNDLES) for (t in b.requiredTags) putIfAbsent(t, b.id)
        for (b in BUNDLES) for (t in b.synonymTags) putIfAbsent(t, b.id)
    }

    /** 取标签对应的意图包；字典外的标签返回只认自身的默认包。 */
    fun bundleFor(uiTag: String): IntentTagBundle {
        val key = normalize(uiTag)
        return BY_ID[key] ?: ALIAS_TO_ID[key]?.let { BY_ID[it] }
            ?: IntentTagBundle(id = key, category = TOPIC, requiredTags = setOf(key))
    }

    fun normalize(tag: String): String = tag.replace('_', ' ').replace('-', ' ').trim().lowercase()

    /** 组合模板。 */
    val COMBOS: List<IntentComboTemplate> = listOf(
        IntentComboTemplate(
            id = "SCHOOL_ROMCOM_DAILY",
            displayName = "校园恋爱日常喜剧",
            requiredGroups = mapOf("校园" to 0.65, "恋爱" to 0.55),
            optionalGroups = setOf("日常", "搞笑"),
            optionalMinSat = 0.45,
            conflictTags = HEAVY_ACTION + DARK + setOf("异世界", "灵异"),
            bonus = 0.18,
            conflictPenalty = 0.15,
        ),
        IntentComboTemplate(
            id = "TEARJERKER",
            displayName = "催泪向",
            requiredGroups = mapOf("催泪" to 0.70),
            optionalGroups = emptySet(),
            optionalMinSat = 0.0,
            conflictTags = setOf("冒险", "热血", "战斗") + LIGHT_COMEDY,
            bonus = 0.10,
            conflictPenalty = 0.15,
        ),
    )
}
