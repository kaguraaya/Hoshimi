package com.acgcompass.domain.taste

/**
 * 口味画像十二维模型使用的标签大类。
 *
 * 每个社区标签经 [TagClassifier] 归入唯一大类；[COMBO]、[COMMENT]、[COMMUNITY] 三类不直接来自单标签：
 * - [COMBO]：由 [TOPIC] + [DEVICE] 两类在高分样本里挖掘出的二元/三元组合（见 BuildTasteProfile）。
 * - [COMMENT]：来自用户短评语义关键词（非单标签）。
 * - [COMMUNITY]：来自作品社区评分 + 票数的弱引导项（非单标签）。
 * - [NOISE]：交叉验证判定为噪声（如与真实 staff 不符的「京阿尼」联想、self-title 泄漏标签），近乎忽略。
 *
 * [defaultWeight] 是十二维线性融合 z(x) 的默认权重（文档「候选层综合打分」与「默认参数」表）：
 * 题材单标签 + 题材组合合计占绝对主导；时间 / 梗 / 社区评分为弱特征。
 */
enum class TasteCategory(val key: String, val defaultWeight: Float) {
    /** 题材单标签：奇幻/科幻/校园/青春/悬疑/恋爱/治愈/惊悚……主干特征。 */
    TOPIC("topic", 0.24f),

    /** 题材组合：奇幻+异世界+智斗、校园+青春+恋爱……从高分样本挖掘的二元/三元组合（最重要的提升点）。 */
    COMBO("combo", 0.18f),

    /** 情节装置：轮回/穿越/党争/大逃杀/智斗/励志……与题材并列但稍低。 */
    DEVICE("device", 0.09f),

    /** 角色原型与 XP：妹妹/姐姐/病娇/伪娘/后宫/黑长直……有用但须受高分作品约束。 */
    XP("xp", 0.12f),

    /** 角色实体：牧濑红莉栖/我妻由乃/五河琴里/亚丝娜……可强解释，仅对同类角色偏好生效。 */
    CHARACTER("character", 0.05f),

    /** staff：导演/脚本/系列构成/原作/人物设定/工作室，用真实 staff 字段校验。 */
    STAFF("staff", 0.08f),

    /** 声优：花泽香菜/竹达彩奈/宫野真守/松冈祯丞……只做加分，不做强召回。 */
    CV("cv", 0.04f),

    /** 改编来源 / 制作属性：轻小说改/游戏改/GAL改/漫画改/制作公司……对「GAL 改偏好」等很有价值。 */
    SOURCE("source", 0.05f),

    /** 时间标签：2014年4月/2011年10月……只反映时代审美或补番区间，弱用。 */
    TIME("time", 0.03f),

    /** 梗 / 社区语气：嘟嘟噜/柴刀/史上最强跟踪狂/抽象……只能弱用，避免梗驱动误判。 */
    MEME("meme", 0.03f),

    /** 评论语义：「慢热但后劲大」「设定有想法」「胃痛」「上头」……从短评抽关键词补标签盲区。 */
    COMMENT("comment", 0.06f),

    /** 社区评分弱引导：Bangumi score + votes，只做下限门槛与极轻微先验。 */
    COMMUNITY("community", 0.03f),

    /** 噪声：交叉验证未通过的标签（self-title 泄漏、与 staff 不符的联想梗），近乎忽略、默认不参与主分。 */
    NOISE("noise", 0.005f),
    ;

    /** 面向用户的简短中文标签（推荐理由 / 调试展示用）。 */
    val label: String
        get() = when (this) {
            TOPIC -> "题材"
            COMBO -> "题材组合"
            DEVICE -> "情节"
            XP -> "角色类型"
            CHARACTER -> "角色"
            STAFF -> "制作阵容"
            CV -> "声优"
            SOURCE -> "来源/制作"
            TIME -> "年代"
            MEME -> "社区梗"
            COMMENT -> "评价倾向"
            COMMUNITY -> "口碑"
            NOISE -> "噪声"
        }

    companion object {
        /** 由单标签分类器产出的大类（不含 COMBO/COMMENT/COMMUNITY 这三类派生维度）。 */
        val SINGLE_TAG_CATEGORIES: List<TasteCategory> =
            listOf(TOPIC, DEVICE, XP, CHARACTER, STAFF, CV, SOURCE, TIME, MEME, NOISE)

        /**
         * 由维度 [key]（AI 升维分类输出 / 缓存持久化值）解析大类；未知 / `null` 返回 `null`
         * （调用方回退本地规则，不编造）。仅接受可由单标签产出的维度（[SINGLE_TAG_CATEGORIES]），
         * 拒绝 COMBO/COMMENT/COMMUNITY 等派生维度，避免 AI 误把单标签塞进这些非单标签维度。
         */
        fun fromKey(key: String?): TasteCategory? {
            val k = key?.trim()?.lowercase().orEmpty()
            if (k.isEmpty()) return null
            SINGLE_TAG_CATEGORIES.firstOrNull { it.key == k }?.let { return it }
            // 不支持 json_schema 的端点可能忽略 enum 约束并返回中文或近义维度；
            // 这里按白名单把明确近义映射回单标签维度，避免可用响应被判非法而整批丢弃（classified=0 误报）。
            // 仅映射到 SINGLE_TAG_CATEGORIES；派生维度（combo/comment/community）与无意义串仍返回 null（不编造）。
            return DIMENSION_ALIASES[k]
        }

        /** 参与「题材组合挖掘」的来源大类（文档：只对题材单标签 + 情节装置做组合）。 */
        val COMBO_SOURCE_CATEGORIES: List<TasteCategory> = listOf(TOPIC, DEVICE)

        /**
         * 维度近义别名 → 单标签维度（白名单，全小写键）。供 [fromKey] 容错模型在不支持
         * 结构化输出时返回的中文 / 近义 dimension；只映射明确近义，绝不含派生维度或无意义串。
         */
        private val DIMENSION_ALIASES: Map<String, TasteCategory> = mapOf(
            "题材" to TOPIC, "类型" to TOPIC, "genre" to TOPIC, "genres" to TOPIC,
            "theme" to TOPIC, "themes" to TOPIC, "topics" to TOPIC,
            "情节" to DEVICE, "情节装置" to DEVICE, "装置" to DEVICE, "plot" to DEVICE,
            "narrative" to DEVICE, "trope" to DEVICE, "tropes" to DEVICE,
            "角色类型" to XP, "萌属性" to XP, "属性" to XP, "moe" to XP, "archetype" to XP,
            "角色" to CHARACTER, "角色名" to CHARACTER, "人物" to CHARACTER,
            "char" to CHARACTER, "characters" to CHARACTER,
            "制作" to STAFF, "制作阵容" to STAFF, "阵容" to STAFF, "staffs" to STAFF,
            "studio" to STAFF, "director" to STAFF,
            "声优" to CV, "配音" to CV, "seiyuu" to CV, "voice" to CV, "va" to CV,
            "来源" to SOURCE, "改编来源" to SOURCE, "改编" to SOURCE,
            "adaptation" to SOURCE, "origin" to SOURCE,
            "年代" to TIME, "时间" to TIME, "年份" to TIME, "year" to TIME,
            "date" to TIME, "season" to TIME,
            "社区梗" to MEME, "梗" to MEME, "黑话" to MEME, "slang" to MEME,
            "噪声" to NOISE, "噪音" to NOISE,
        )
    }
}
