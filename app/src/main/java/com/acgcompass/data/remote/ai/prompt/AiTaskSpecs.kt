package com.acgcompass.data.remote.ai.prompt

import com.acgcompass.data.remote.ai.AiResponseFormat
import com.acgcompass.domain.ai.AiTask
import com.acgcompass.domain.model.AiTaskType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 把一类 [AiTask] 解析为调用所需的「系统提示词 + 目标 JSON Schema + 输出上限」。
 *
 * 这是 `data/remote/ai/prompt` 的核心：将领域层的任务描述（[AiTask]）映射为 provider 可消费的
 * 提示词与结构化输出约束。所有系统提示词都内联 [SpoilerGuard.SYSTEM_PROMPT_RULES]（强制剧透保护）。
 *
 * @property systemPrompt    完整系统提示词（角色 + 输出格式说明 + 剧透保护规则）。
 * @property responseFormat  目标输出格式（[AiResponseFormat.JsonSchema]）；不支持的 provider 由其降级。
 * @property maxOutputTokens 该任务的默认输出 token 上限（用于成本估算与请求约束）。
 */
data class AiTaskSpec(
    val systemPrompt: String,
    val responseFormat: AiResponseFormat,
    val maxOutputTokens: Int,
)

/**
 * [AiTask] → [AiTaskSpec] 解析器。固定每类任务的 schema 与提示词模板。
 */
object AiTaskSpecs {

    /**
     * 解析任务的提示词与目标 schema。
     *
     * 推理型模型会把 reasoning token 计入 completion 预算，因此结构化任务需要为完整 JSON
     * 预留充足输出空间。`max_tokens` 是上限，不会强制非推理模型消耗全部额度。
     */
    fun specFor(task: AiTask<*>): AiTaskSpec = when (task) {
        is AiTask.SpoilerRadar -> AiTaskSpec(
            systemPrompt = spoilerRadarPrompt(),
            responseFormat = AiResponseFormat.JsonSchema("spoiler_radar", spoilerRadarSchema()),
            maxOutputTokens = 1600,
        )
        is AiTask.TasteProfile -> AiTaskSpec(
            systemPrompt = tasteProfilePrompt(),
            responseFormat = AiResponseFormat.JsonSchema("taste_profile", tasteProfileSchema()),
            maxOutputTokens = 1600,
        )
        is AiTask.TonightRecommender -> AiTaskSpec(
            systemPrompt = recommenderPrompt(task.indecisive),
            responseFormat = AiResponseFormat.JsonSchema(
                name = "tonight_recommender",
                schema = recommenderSchema(task.indecisive),
            ),
            maxOutputTokens = 1200,
        )
        is AiTask.RouteMap -> AiTaskSpec(
            systemPrompt = routeMapPrompt(),
            responseFormat = AiResponseFormat.JsonSchema("route_map", routeMapSchema()),
            maxOutputTokens = 2000,
        )
        is AiTask.TasteMatch -> AiTaskSpec(
            systemPrompt = tasteMatchPrompt(),
            responseFormat = AiResponseFormat.JsonSchema("taste_match", tasteMatchSchema()),
            maxOutputTokens = 1200,
        )
        is AiTask.TagClassify -> AiTaskSpec(
            systemPrompt = tagClassifyPrompt(),
            responseFormat = AiResponseFormat.JsonSchema("tag_classify", tagClassifySchema()),
            // 每标签一行 {tag,dimension}，一批数十标签约需千级 token；预留充足空间避免 JSON 被截断。
            maxOutputTokens = 2000,
        )
        is AiTask.VndbTranslation -> AiTaskSpec(
            systemPrompt = vndbTranslationPrompt(),
            responseFormat = AiResponseFormat.JsonSchema("vndb_translation", vndbTranslationSchema()),
            maxOutputTokens = 1800,
        )
    }

    /**
     * 「修复成指定格式」二次请求的系统提示词。
     *
     * 在首次输出缺字段 / JSON 损坏时使用：要求模型**仅**输出符合给定 schema 的合法 JSON，
     * 不要解释、不要使用 Markdown 代码块；同样保留剧透保护规则。
     */
    fun repairPrompt(originalSystemPrompt: String): String = buildString {
        appendLine("上一轮输出不是合法 JSON 或缺少必需字段。仅输出符合下述要求的合法 JSON 对象。")
        appendLine("严格要求：")
        appendLine("- 只输出 JSON 对象本身，不要任何解释文字，不要使用 Markdown 代码块或反引号。")
        appendLine("- 必须包含目标格式要求的全部字段；无法确定的字段使用合理的空值（空字符串 / 空数组 / 0），并降低 confidence。")
        appendLine()
        append(originalSystemPrompt)
    }

    // ---- 系统提示词模板（均内联剧透保护规则） ----

    private fun spoilerRadarPrompt(): String = buildString {
        appendLine("任务：基于给定标题、类型、标签、简介与社区短评，生成 ACG 作品的无剧透评价雷达。")
        appendLine("内容应具体且有信息量，帮助用户评估作品是否适合观看，避免空泛表述。")
        appendLine("各字段填写指引：")
        appendLine(
            "- overallImpression（总体印象）：用 2-4 句概括作品的风格定位、节奏走向" +
                "（可分前期/中期/后期的观感，如「前期铺垫偏慢、中段渐入佳境、后段高潮」）、" +
                "以及它最突出的特质。只谈风格/节奏/氛围，不剧透具体情节。",
        )
        appendLine("- pros（好评点）：3-5 条，社区/大众普遍称赞之处（作画、音乐、人物塑造、节奏、情感冲击等）。")
        appendLine("- controversies（争议/吐槽点）：2-4 条，民间存在分歧或常见吐槽（节奏拖沓、某些设定争议、烂尾质疑等），中性陈述不下定论。")
        appendLine("- pitfalls（雷点/需注意）：2-4 条，可能劝退的点（致郁、猎奇、深夜致郁、需要前置作品、长篇坑等）。")
        appendLine("- suitableFor / notSuitableFor：分别给出适合 / 不适合的人群画像。")
        appendLine("- watchTiming（观看时机）：建议什么状态下看（如「适合放松时」「需要专注追」「等完结再补」）。")
        appendLine("- confidence：以上判断的置信度（0~1）；资料越少数值越低，有合理依据时不应为 0。")
        appendLine("- sources：判断所依据的信息类型（如标签、简介、社区短评）。")
        appendLine("即便资料有限，也要尽量基于题材常识与已知口碑给出**具体**判断，不要只说「资料有限无法评价」。")
        appendLine("只评价风格 / 节奏 / 题材 / 口碑 / 争议，绝不复述具体剧情或结局。输出必须是符合目标 schema 的合法 JSON。")
        appendLine()
        append(SpoilerGuard.SYSTEM_PROMPT_RULES)
    }

    private fun tasteProfilePrompt(): String = buildString {
        appendLine("任务：基于用户的评分、状态、短评与标签统计生成口味画像，")
        appendLine("总结其高分倾向标签、低分倾向标签、常用短评词、常见搁置类型、评分习惯与口味称号。")
        appendLine("当样本不足时，必须降低 confidence 并在描述中使用「可能 / 倾向于」等非绝对措辞，不要下绝对结论。")
        appendLine("输出必须是符合目标 schema 的 JSON。")
        appendLine()
        append(SpoilerGuard.SYSTEM_PROMPT_RULES)
    }

    private fun recommenderPrompt(indecisive: Boolean): String = buildString {
        appendLine("任务：从给定候选作品中生成「今晚看什么」推荐；不得选择列表外或用户已完成的作品。")
        if (indecisive) {
            appendLine("当前为单一推荐模式：只给出唯一一个推荐 pick，并给出明确、可解释的理由。")
        } else {
            appendLine("给出三个推荐：safe（稳妥）、gamble（探索）、wildcard（随机/冷门）。每个都要带可解释的理由。")
        }
        appendLine("每个推荐必须引用候选列表中的 workId。输出必须是符合目标 schema 的 JSON。")
        appendLine()
        append(SpoilerGuard.SYSTEM_PROMPT_RULES)
    }

    private fun routeMapPrompt(): String = buildString {
        appendLine("任务：基于给定的系列关联作品资料，输出补番路线节点的观看建议与顺序。")
        appendLine("recommendation 取值限定为 MUST / OPTIONAL / SKIP / RECAP。")
        appendLine("如果资料不足以确定确切的观看顺序，必须把 routeConfirmed 设为 false，且不要编造一个确定的 orderIndex 顺序。")
        appendLine("输出必须是符合目标 schema 的 JSON。")
        appendLine()
        append(SpoilerGuard.SYSTEM_PROMPT_RULES)
    }

    private fun tagClassifyPrompt(): String = buildString {
        appendLine("任务：将每个 Bangumi 社区标签归入下列唯一维度之一，")
        appendLine("只输出维度的英文 key。维度定义：")
        appendLine("- topic（题材）：类型/情绪/设定/元素，如 奇幻、治愈、校园、异世界、悬疑、后宫、百合。")
        appendLine("- device（情节装置）：叙事/结构手法，如 时间循环、下克上、多线叙事、无限流、叙述性诡计。")
        appendLine("- xp（角色类型/萌属性）：角色原型或属性，如 病娇、傲娇、黑长直、猫娘、大小姐。")
        appendLine("- character（角色名）：具体登场角色的人名，如 牧濑红莉栖、亚丝娜。")
        appendLine("- staff（制作人员）：导演/脚本/原作/人设等真实人名或职能，如 虚渊玄、新海诚。")
        appendLine("- cv（声优名）：配音演员姓名，如 花泽香菜、宫野真守。")
        appendLine("- source（改编来源/制作方）：如 轻小说改、游戏改、漫画改、京都动画、ufotable。")
        appendLine("- time（年代/季度）：如 2011年10月、2014年4月、2024春。")
        appendLine("- meme（社区梗/评价黑话）：如 神作、爷青回、致郁系神作、经费燃烧、名作之壁。")
        appendLine("- noise（噪声）：无区分度的媒介格式/放送状态/地区，或无意义标签，如 TV、剧场版、完结、日本。")
        appendLine("分类原则：优先判断是否属于 device/xp/character/staff/cv/source/time/meme/noise 这些更具体的维度；")
        appendLine("确实是内容题材才归 topic；无法判断或明显无信息量的归 noise，不要编造。")
        appendLine("topic 必须是可复用的语义类别，绝不能是作品专名、人名、罗马音标题或标题碎片。")
        appendLine("例如 Steins、Gate 这类从《Steins;Gate》拆出的名字片段必须归 noise，不能归 topic。")
        appendLine("开发商、发行商和制作方名称属于 source 或 noise；例如「空想科学」绝不能归 topic。")
        appendLine("必须为输入里的每个标签各输出一条 {tag, dimension}，tag 原样回填。")
        appendLine("输出格式（重要）：必须返回一个 **JSON 对象**，把所有分类结果放进顶层的 \"items\" 数组，")
        appendLine("**不要**直接返回顶层数组。形如：")
        appendLine(
            "{\"items\":[{\"tag\":\"百合\",\"dimension\":\"topic\"}," +
                "{\"tag\":\"神作\",\"dimension\":\"meme\"}],\"confidence\":0.9}",
        )
        appendLine("只输出该 JSON 对象本身，不要解释文字、不要 Markdown 代码块。")
        appendLine()
        append(SpoilerGuard.SYSTEM_PROMPT_RULES)
    }

    private fun tasteMatchPrompt(): String = buildString {
        appendLine("任务：根据作品资料（类型、标签、简介、社区评分）与用户口味画像")
        appendLine("（高分倾向标签、低分倾向标签、评分习惯、近期评分作品与历史最高/最低分作品），")
        appendLine("判断作品与该用户的契合度，并给出可验证的具体依据。")
        appendLine("判定原则（最重要）：以**个人口味契合**为主轴，社区评分只是弱参考——绝不能因为社区高分就抬高契合度；")
        appendLine("当作品与用户低分倾向 / 雷区标签重合时，即使它是大众口碑神作，也应给出低契合分，并在 riskReasons 说明原因。")
        appendLine("用户近期评分比早期评分更能代表当前口味，做类比推理时优先参考其近期评分的作品。")
        appendLine("评分要求（重要）：matchScore 为 0–100 整数，必须有区分度，**不要总是给 75–85 的万金油分数**：")
        appendLine("- 当作品题材/风格与用户高分作品高度一致 → 85–98；")
        appendLine("- 明显契合 → 70–84；一般/中性 → 45–69；")
        appendLine("- 与用户低分倾向重合或明显不对味 → 15–44；强烈冲突 → 0–15。")
        appendLine("结合用户高分和低分作品做类比推理，并明确指出相似或冲突的维度。")
        appendLine("likedReasons / riskReasons 要具体、针对该用户，而非泛泛而谈。confidence 反映资料充分度。")
        appendLine("只基于给定信息，不复述具体剧情。输出必须是符合目标 schema 的合法 JSON。")
        appendLine()
        append(SpoilerGuard.SYSTEM_PROMPT_RULES)
    }

    // ---- JSON Schema 构造 ----

    private fun stringArraySchema(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        putJsonObject("items") { put("type", "string") }
    }

    private fun objectSchema(properties: JsonObject, required: List<String>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", properties)
        put("required", required.toJsonStringArray())
        put("additionalProperties", false)
    }

    private fun List<String>.toJsonStringArray(): JsonArray =
        buildJsonArray { this@toJsonStringArray.forEach { add(JsonPrimitive(it)) } }

    private fun confidenceSchema(): JsonObject = buildJsonObject {
        put("type", "number")
        put("description", "置信度 0.0–1.0；无法判断时取较低值，不编造")
        put("minimum", 0)
        put("maximum", 1)
    }

    private fun tasteMatchSchema(): JsonObject = objectSchema(
        properties = buildJsonObject {
            putJsonObject("matchScore") {
                put("type", "integer")
                put("description", "匹配度 0–100；不确定取较低值")
                put("minimum", 0)
                put("maximum", 100)
            }
            put("likedReasons", stringArraySchema("可能喜欢的理由"))
            put("riskReasons", stringArraySchema("可能不喜欢 / 需注意的理由"))
            put("confidence", confidenceSchema())
        },
        required = listOf("matchScore", "likedReasons", "riskReasons", "confidence"),
    )

    private fun spoilerRadarSchema(): JsonObject = objectSchema(
        properties = buildJsonObject {
            putJsonObject("overallImpression") {
                put("type", "string")
                put("description", "总体印象（无剧透）")
            }
            put("pros", stringArraySchema("优点"))
            put("controversies", stringArraySchema("争议点"))
            put("pitfalls", stringArraySchema("雷点 / 需注意"))
            put("suitableFor", stringArraySchema("适合人群"))
            put("notSuitableFor", stringArraySchema("不适合人群"))
            putJsonObject("watchTiming") {
                put("type", "string")
                put("description", "观看时机建议")
            }
            put("confidence", confidenceSchema())
            put("sources", stringArraySchema("数据来源标签"))
        },
        required = listOf(
            "overallImpression", "pros", "controversies", "pitfalls",
            "suitableFor", "notSuitableFor", "watchTiming", "confidence", "sources",
        ),
    )

    private fun tasteProfileSchema(): JsonObject = objectSchema(
        properties = buildJsonObject {
            put("highScoreTags", stringArraySchema("高分倾向标签"))
            put("lowScoreTags", stringArraySchema("低分倾向标签"))
            put("commonReviewWords", stringArraySchema("常用短评词"))
            put("droppedTypes", stringArraySchema("常见搁置 / 抛弃类型"))
            putJsonObject("scoringHabit") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("strictness") { put("type", "string") }
                    putJsonObject("averageScore") { put("type", "number") }
                    putJsonObject("highScoreRarity") { put("type", "string") }
                    putJsonObject("commonScoreBand") { put("type", "string") }
                }
                put(
                    "required",
                    listOf("strictness", "averageScore", "highScoreRarity", "commonScoreBand")
                        .toJsonStringArray(),
                )
                put("additionalProperties", false)
            }
            put("titles", stringArraySchema("口味 / 补番人格称号"))
            put("confidence", confidenceSchema())
        },
        required = listOf(
            "highScoreTags",
            "lowScoreTags",
            "commonReviewWords",
            "droppedTypes",
            "scoringHabit",
            "titles",
            "confidence",
        ),
    )

    private fun pickSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("workId") { put("type", "string") }
            putJsonObject("reason") { put("type", "string") }
        }
        put("required", listOf("workId", "reason").toJsonStringArray())
        put("additionalProperties", false)
    }

    private fun recommenderSchema(indecisive: Boolean): JsonObject = if (indecisive) {
        objectSchema(
            properties = buildJsonObject {
                put("pick", pickSchema())
                put("confidence", confidenceSchema())
            },
            required = listOf("pick", "confidence"),
        )
    } else {
        objectSchema(
            properties = buildJsonObject {
                put("safe", pickSchema())
                put("gamble", pickSchema())
                put("wildcard", pickSchema())
                put("confidence", confidenceSchema())
            },
            required = listOf("safe", "gamble", "wildcard", "confidence"),
        )
    }

    private fun vndbTranslationPrompt(): String = buildString {
        appendLine("任务：把给定的视觉小说原文简介忠实翻译为简体中文。")
        appendLine("保留段落、专名和语气；不要扩写、评价、补充剧情或猜测缺失内容。")
        appendLine("输出 JSON：text 为译文，confidence 为 0 到 1。")
        appendLine("若输入已经主要是中文，原样整理后返回；不得输出输入之外的信息。")
        appendLine()
        append(SpoilerGuard.SYSTEM_PROMPT_RULES)
    }

    private fun routeMapSchema(): JsonObject = objectSchema(
        properties = buildJsonObject {
            putJsonObject("nodes") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("workId") { put("type", "string") }
                        putJsonObject("relationType") { put("type", "string") }
                        putJsonObject("recommendation") {
                            put("type", "string")
                            put(
                                "enum",
                                listOf("MUST", "OPTIONAL", "SKIP", "RECAP").toJsonStringArray(),
                            )
                        }
                        putJsonObject("orderIndex") { put("type", "integer") }
                    }
                    put(
                        "required",
                        listOf("workId", "relationType", "recommendation", "orderIndex")
                            .toJsonStringArray(),
                    )
                    put("additionalProperties", false)
                }
            }
            put("confidence", confidenceSchema())
            putJsonObject("routeConfirmed") {
                put("type", "boolean")
                put("description", "资料不足以确定顺序时为 false（路线待确认）")
            }
        },
        required = listOf("nodes", "confidence", "routeConfirmed"),
    )

    private fun tagClassifySchema(): JsonObject = objectSchema(
        properties = buildJsonObject {
            putJsonObject("items") {
                put("type", "array")
                put("description", "每个输入标签的升维分类结果")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("tag") {
                            put("type", "string")
                            put("description", "原始标签，原样回填")
                        }
                        putJsonObject("dimension") {
                            put("type", "string")
                            put(
                                "enum",
                                listOf(
                                    "topic", "device", "xp", "character", "staff",
                                    "cv", "source", "time", "meme", "noise",
                                ).toJsonStringArray(),
                            )
                        }
                    }
                    put("required", listOf("tag", "dimension").toJsonStringArray())
                    put("additionalProperties", false)
                }
            }
            put("confidence", confidenceSchema())
        },
        required = listOf("items", "confidence"),
    )

    private fun vndbTranslationSchema(): JsonObject = objectSchema(
        properties = buildJsonObject {
            putJsonObject("text") { put("type", "string") }
            put("confidence", confidenceSchema())
        },
        required = listOf("text", "confidence"),
    )

    /** 任务类型 → 人类可读名（用于诊断 / 日志，非敏感）。 */
    fun displayName(type: AiTaskType): String = when (type) {
        AiTaskType.SPOILER_RADAR -> "防剧透雷达"
        AiTaskType.TASTE_PROFILE -> "口味画像"
        AiTaskType.RECOMMENDER -> "今晚推荐"
        AiTaskType.ROUTE_MAP -> "路线图"
        AiTaskType.TASTE_MATCH -> "口味匹配"
        AiTaskType.TAG_CLASSIFY -> "AI 标签升维分类"
        AiTaskType.VNDB_TRANSLATION -> "VNDB 简介翻译"
        AiTaskType.UNKNOWN -> "未知任务"
    }
}
