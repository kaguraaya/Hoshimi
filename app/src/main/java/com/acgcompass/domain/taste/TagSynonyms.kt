package com.acgcompass.domain.taste

/**
 * 社区标签同义归一（纯数据）。
 *
 * Bangumi 标签是用户自由输入的：同一个意思常被写成好几种（「治愈 / 治愈系」「催泪 / 催淚」
 * 「热血 / 热血系」「校园 / 学园」）。不归一时它们在画像里是互不相干的键——你给十部「治愈」高分，
 * 遇到只标了「治愈系」的作品却完全对不上。
 *
 * 只收录**写法不同、含义相同**的确定性同义（繁简、带不带「系 / 向 / 番」后缀、常见别称）；
 * 含义相近但不等价的（「恋爱」与「纯爱」、「搞笑」与「吐槽」）不在此合并——那属于「今晚看什么」
 * 意图包里的近义关系，按折扣计，不应在长期画像里被当成同一个标签。
 */
object TagSynonyms {

    private val CANONICAL: Map<String, String> = buildMap {
        fun group(canonical: String, vararg variants: String) = variants.forEach { put(it, canonical) }
        group("治愈", "治愈系", "治癒", "治癒系", "治愈向")
        group("催泪", "催淚", "催泪系", "泪腺崩坏")
        group("致郁", "致郁系", "致鬱")
        group("热血", "热血系", "熱血", "燃系")
        group("搞笑", "搞笑系", "爆笑", "搞笑番")
        group("校园", "学园", "學園", "校園", "学院")
        group("恋爱", "戀愛", "爱情", "愛情", "恋爱番")
        group("日常", "日常系", "日常番")
        group("奇幻", "奇幻系", "幻想")
        group("科幻", "sf", "sci-fi", "sci fi")
        group("悬疑", "懸疑", "悬疑系")
        group("战斗", "戰鬥", "战斗系", "打斗")
        group("百合", "gl", "百合向")
        group("后宫", "後宮", "后宫向", "harem")
        group("机战", "机战片", "萝卜", "机甲", "機戰")
        group("冒险", "冒險")
        group("推理", "推理系", "侦探")
        group("恐怖", "恐怖系")
        group("猎奇", "獵奇")
        group("异世界", "異世界", "异界")
        group("音乐", "音樂")
        group("运动", "體育", "体育", "运动番")
        group("魔法少女", "马猴烧酒", "魔法少女系")
        group("轮回", "輪迴")
        group("病娇", "病嬌", "ヤンデレ")
        group("傲娇", "傲嬌", "ツンデレ")
        group("伪娘", "偽娘", "男の娘")
        group("萝莉", "蘿莉", "loli", "ロリ")
        group("妹妹", "妹", "妹系")
        group("青梅竹马", "幼驯染", "幼馴染")
        group("群像", "群像剧", "群像劇")
        group("励志", "勵志")
        group("成长", "成長")
        group("青春", "青春系")
        group("萌", "萌系", "萌番")
    }

    /** 清洗后的标签 → 规范写法；没有同义项时原样返回。 */
    fun canonical(cleanedTag: String): String = CANONICAL[cleanedTag] ?: cleanedTag
}
