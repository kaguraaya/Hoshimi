package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * AI 标签升维分类集成回归：覆盖缓存只作用于本地规则暂归为题材的未知标签，
 * 已被词典 / 正则 / 交叉验证命中的标签绝不受影响；缓存为空时行为与之前完全一致（AI 未配置全回退本地）。
 *
 * 覆盖：[TagClassifier.isUnknownTopicFallback] 候选判定、[TagClassifier.classify] override 生效 / 隔离、
 * [TasteCategory.fromKey] 维度解析、[TasteRawScorer.featurize] override 透传。
 * 断言以设计上可保证的性质为主，避免依赖具体词典内容（选用确定在/不在词典的标签）。
 */
class TagClassifierAiOverrideTest : StringSpec({

    // 一个确定「不在任何词典 / 正则 / 元数据」的未知标签：兜底为题材（TOPIC）。
    val unknown = "zzunknowntag"

    "isUnknownTopicFallback：未知标签为升维候选" {
        TagClassifier.isUnknownTopicFallback(unknown) shouldBe true
    }

    "isUnknownTopicFallback：词典 / 正则命中的标签不是候选" {
        // 情节装置词典、XP 词典、梗词典、时间正则、来源正则命中者均非兜底候选。
        TagClassifier.isUnknownTopicFallback("时间循环") shouldBe false
        TagClassifier.isUnknownTopicFallback("病娇") shouldBe false
        TagClassifier.isUnknownTopicFallback("神作") shouldBe false
        TagClassifier.isUnknownTopicFallback("2011年10月") shouldBe false
        TagClassifier.isUnknownTopicFallback("轻小说改") shouldBe false
    }

    "标题罗马音碎片 Steins 和 Gate 被确定性识别为噪声" {
        val context = TagClassifier.Context.of(titles = listOf("Steins;Gate", "命运石之门"))
        TagClassifier.isUnknownTopicFallback("Steins", context) shouldBe false
        TagClassifier.isUnknownTopicFallback("Gate", context) shouldBe false
        TagClassifier.classify("Steins", context) shouldBe TasteCategory.NOISE
        TagClassifier.classify("Gate", context) shouldBe TasteCategory.NOISE
    }

    "Staff 和角色的罗马音姓名碎片不进入题材" {
        val context = TagClassifier.Context.of(
            staff = listOf("Gen Urobuchi"),
            characters = listOf("Kurisu Makise"),
        )
        TagClassifier.classify("Urobuchi", context) shouldBe TasteCategory.NOISE
        TagClassifier.classify("Kurisu", context) shouldBe TasteCategory.NOISE
    }

    "开发商空想科学不进入题材" {
        TagClassifier.isUnknownTopicFallback("空想科学") shouldBe false
        TagClassifier.classify("空想科学") shouldBe TasteCategory.SOURCE
        val context = TagClassifier.Context.of(sources = listOf("MAGES.", "Nitroplus"))
        TagClassifier.classify("MAGES.", context) shouldBe TasteCategory.SOURCE
        TagClassifier.classify("Nitroplus", context) shouldBe TasteCategory.SOURCE
    }

    "classify：空 override 时未知标签仍兜底为题材（与之前一致）" {
        TagClassifier.classify(unknown) shouldBe TasteCategory.TOPIC
        TagClassifier.classify(unknown, overrides = emptyMap()) shouldBe TasteCategory.TOPIC
    }

    "classify：override 命中则把兜底未知标签改判为更精确维度" {
        val overrides = mapOf(unknown to TasteCategory.DEVICE)
        TagClassifier.classify(unknown, overrides = overrides) shouldBe TasteCategory.DEVICE
    }

    "classify：override 不影响词典 / 正则 / 元数据已命中的标签" {
        // 即使 override 硬把这些标签映射到别的维度，本地精确规则优先，override 被忽略。
        val hostile = mapOf(
            "时间循环" to TasteCategory.TOPIC,
            "神作" to TasteCategory.TOPIC,
            "2011年10月" to TasteCategory.DEVICE,
            "轻小说改" to TasteCategory.XP,
        )
        TagClassifier.classify("时间循环", overrides = hostile) shouldBe TasteCategory.DEVICE
        TagClassifier.classify("神作", overrides = hostile) shouldBe TasteCategory.MEME
        TagClassifier.classify("2011年10月", overrides = hostile) shouldBe TasteCategory.TIME
        TagClassifier.classify("轻小说改", overrides = hostile) shouldBe TasteCategory.SOURCE
    }

    "TasteCategory.fromKey：合法单标签维度可解析（trim + 小写）" {
        TasteCategory.fromKey("device") shouldBe TasteCategory.DEVICE
        TasteCategory.fromKey(" XP ") shouldBe TasteCategory.XP
        TasteCategory.fromKey("NOISE") shouldBe TasteCategory.NOISE
        TasteCategory.fromKey("topic") shouldBe TasteCategory.TOPIC
    }

    "TasteCategory.fromKey：派生 / 非法 / 空维度返回 null" {
        TasteCategory.fromKey("combo") shouldBe null
        TasteCategory.fromKey("community") shouldBe null
        TasteCategory.fromKey("comment") shouldBe null
        TasteCategory.fromKey("bogus") shouldBe null
        TasteCategory.fromKey("") shouldBe null
        TasteCategory.fromKey(null) shouldBe null
    }

    "TasteCategory.fromKey 将中英文近义别名映射到单标签维度" {
        // 模型在不支持结构化输出时常返回中文 / 近义 dimension；白名单别名容错，避免可用响应被整批丢弃。
        TasteCategory.fromKey("题材") shouldBe TasteCategory.TOPIC
        TasteCategory.fromKey("genre") shouldBe TasteCategory.TOPIC
        TasteCategory.fromKey("情节") shouldBe TasteCategory.DEVICE
        TasteCategory.fromKey("moe") shouldBe TasteCategory.XP
        TasteCategory.fromKey("角色") shouldBe TasteCategory.CHARACTER
        TasteCategory.fromKey("声优") shouldBe TasteCategory.CV
        TasteCategory.fromKey("改编") shouldBe TasteCategory.SOURCE
        TasteCategory.fromKey("年代") shouldBe TasteCategory.TIME
        TasteCategory.fromKey("梗") shouldBe TasteCategory.MEME
        // 别名大小写不敏感 + trim。
        TasteCategory.fromKey(" Genre ") shouldBe TasteCategory.TOPIC
    }

    "featurize：空 override 时未知标签进 TOPIC；不进 DEVICE/XP" {
        val feature = WorkFeature(
            subjectId = "w1",
            tagCounts = listOf(TagCount(unknown, 10), TagCount("时间循环", 5)),
        )
        val f = TasteRawScorer.featurize(feature)
        (f.byCategory[TasteCategory.TOPIC]?.containsKey(unknown) == true) shouldBe true
        (f.byCategory[TasteCategory.DEVICE]?.containsKey(unknown) == true) shouldBe false
        // 词典命中的「时间循环」始终进 DEVICE，不受影响。
        (f.byCategory[TasteCategory.DEVICE]?.containsKey("时间循环") == true) shouldBe true
    }

    "featurize：override 把未知标签改归到指定维度（透传生效）" {
        val feature = WorkFeature(subjectId = "w2", tagCounts = listOf(TagCount(unknown, 10)))
        val f = TasteRawScorer.featurize(feature, mapOf(unknown to TasteCategory.XP))
        (f.byCategory[TasteCategory.XP]?.containsKey(unknown) == true) shouldBe true
        (f.byCategory[TasteCategory.TOPIC]?.containsKey(unknown) == true) shouldBe false
    }

    "featurize：override 判为 NOISE 的标签被丢弃（不进任何维度）" {
        val feature = WorkFeature(subjectId = "w3", tagCounts = listOf(TagCount(unknown, 10)))
        val f = TasteRawScorer.featurize(feature, mapOf(unknown to TasteCategory.NOISE))
        f.byCategory.values.any { it.containsKey(unknown) } shouldBe false
    }
})
