package com.acgcompass.data.taste

import com.acgcompass.domain.ai.TagClassifyOutput
import com.acgcompass.domain.ai.TagDimensionAssignment
import com.acgcompass.domain.taste.TasteCategory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * [resolveTagDimensionAssignments] 的命中、回退和丢弃规则回归测试。
 *
 * 覆盖根因修复：模型在不支持 json_schema 的中转站上常改写 tag 或用中文 / 近义 dimension，导致旧逻辑
 * 「key !in batchKeys 或 fromKey==null」整批丢弃 → classified=0 → 误报「AI 未返回可用结果」。
 * 新逻辑只接受请求集合内的 tag，并允许维度别名容错；对改写、对不上或非法维度的结果全部丢弃。
 */
class AiTagResolveTest : StringSpec({

    fun output(vararg items: Pair<String, String>): TagClassifyOutput = TagClassifyOutput(
        items = items.map { TagDimensionAssignment(tag = it.first, dimension = it.second) },
        confidence = 0.8f,
    )

    "精确命中：AI 回填 tag 落在本批 + 合法维度 → 采用" {
        val out = output("异世界" to "topic", "后宫" to "xp")
        resolveTagDimensionAssignments(out, listOf("异世界", "后宫")) shouldBe listOf(
            "异世界" to TasteCategory.TOPIC,
            "后宫" to TasteCategory.XP,
        )
    }

    "AI 改写了 tag：即使 items 数量一致也不能按位置猜测回填" {
        // 结果中的专名与请求集合不一致时应丢弃，避免把分类错配到另一部作品。
        val out = output("Isekai" to "topic", "Harem" to "xp")
        resolveTagDimensionAssignments(out, listOf("异世界", "后宫")) shouldBe emptyList()
    }

    "数量不一致且 tag 对不上 → 丢弃（不猜位置，避免错配）" {
        val out = output("乱写标签" to "topic")
        resolveTagDimensionAssignments(out, listOf("异世界", "后宫", "治愈")) shouldBe emptyList()
    }

    "维度中文近义别名（题材）→ 经 fromKey 归一为 TOPIC" {
        val out = output("异世界" to "题材")
        resolveTagDimensionAssignments(out, listOf("异世界")) shouldBe listOf("异世界" to TasteCategory.TOPIC)
    }

    "非法维度 → 丢弃（不编造）" {
        val out = output("异世界" to "bogus")
        resolveTagDimensionAssignments(out, listOf("异世界")) shouldBe emptyList()
    }

    "同一标签重复 → 去重只保留首个" {
        val out = output("异世界" to "topic", "异世界" to "device")
        resolveTagDimensionAssignments(out, listOf("异世界", "异世界")) shouldBe listOf("异世界" to TasteCategory.TOPIC)
    }

    "空 items → 空结果" {
        resolveTagDimensionAssignments(
            TagClassifyOutput(items = emptyList(), confidence = 0f),
            listOf("异世界"),
        ) shouldBe emptyList()
    }
})
