package com.acgcompass.feature.recommender

import com.acgcompass.domain.model.BacklogItem
import com.acgcompass.domain.model.CompletionCost
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 *
 * 覆盖输入约束、时间预算、作品卡片映射等不依赖 Android 的推荐器行为。
 */
class RecommenderContractTest : StringSpec({

    fun work(id: String, completionCost: CompletionCost? = null): Work = Work(
        id = id,
        titles = Titles(canonical = "作品-$id"),
        mediaType = MediaType.ANIME,
        primarySource = SourceId.BANGUMI,
        completionCost = completionCost,
    )

    fun item(workId: String, dustDays: Int = 0): BacklogItem =
        BacklogItem(workId = workId, addedAt = 0L, dustDays = dustDays)

    // region 输入可提交性

    "未选择时间不可提交" {
        RecommenderInput().canSubmit.shouldBeFalse()
    }

    "选择时间后可提交（标签 / 接受程度可空）" {
        RecommenderInput(time = TimeBudget.ONE_HOUR).canSubmit.shouldBeTrue()
    }

    // endregion

    // region 时间 → availableMinutes

    "周末通宵不限时长（availableMinutes 为 null）" {
        val c = RecommenderInput(time = TimeBudget.WEEKEND).toDrawCriteria(RecommendationKind.SAFE)
        c.availableMinutes.shouldBeNull()
    }

    "时间档映射到对应可用分钟" {
        RecommenderInput(time = TimeBudget.TWENTY_MIN)
            .toDrawCriteria(RecommendationKind.SAFE).availableMinutes shouldBe 20
        RecommenderInput(time = TimeBudget.TWO_THREE_HOURS)
            .toDrawCriteria(RecommendationKind.SAFE).availableMinutes shouldBe 180
    }

    "统一候选按完结成本应用时间预算" {
        work("tonight", CompletionCost.TONIGHT).fitsTimeBudget(TimeBudget.ONE_HOUR).shouldBeFalse()
        work("tonight", CompletionCost.TONIGHT).fitsTimeBudget(TimeBudget.TWO_THREE_HOURS).shouldBeTrue()
        work("weekend", CompletionCost.WEEKEND).fitsTimeBudget(TimeBudget.TWO_THREE_HOURS).shouldBeFalse()
        work("weekend", CompletionCost.WEEKEND).fitsTimeBudget(TimeBudget.WEEKEND).shouldBeTrue()
        work("long", CompletionCost.LONG_HAUL).fitsTimeBudget(TimeBudget.TWO_THREE_HOURS).shouldBeFalse()
    }

    "成本未知时不因时间预算误删候选" {
        work("unknown").fitsTimeBudget(TimeBudget.TWENTY_MIN).shouldBeTrue()
    }

    // endregion

    // region 三档风险白名单逐级放宽

    "稳妥仅容忍用户显式接受的风险" {
        val input = RecommenderInput(
            time = TimeBudget.WEEKEND,
            acceptances = setOf(AcceptanceOption.DEPRESSING),
        )
        input.toDrawCriteria(RecommendationKind.SAFE).riskTolerance shouldBe setOf("致郁")
    }

    "探索推荐在用户接受基础上温和展开" {
        val input = RecommenderInput(
            time = TimeBudget.WEEKEND,
            acceptances = setOf(AcceptanceOption.DEPRESSING),
        )
        val tolerance = input.toDrawCriteria(RecommendationKind.GAMBLE).riskTolerance
        tolerance shouldContainAll setOf("致郁", "慢热")
    }

    "随机推荐容忍全部已知风险" {
        val input = RecommenderInput(time = TimeBudget.WEEKEND)
        val tolerance = input.toDrawCriteria(RecommendationKind.WILDCARD).riskTolerance
        tolerance shouldContainAll ALL_RISK_TAGS
    }

    "勾选烧脑后稳妥档才容忍烧脑" {
        RecommenderInput(time = TimeBudget.WEEKEND).toDrawCriteria(RecommendationKind.SAFE)
            .riskTolerance shouldNotContain "烧脑"
        RecommenderInput(time = TimeBudget.WEEKEND, acceptances = setOf(AcceptanceOption.BRAIN_BURN))
            .toDrawCriteria(RecommendationKind.SAFE).riskTolerance shouldContainAll setOf("烧脑")
    }

    // endregion

    // region 标签与硬过滤

    "稳妥 / 探索保留标签命中，随机推荐忽略标签" {
        val input = RecommenderInput(
            time = TimeBudget.WEEKEND,
            selectedTags = setOf("轻松", "热血"),
        )
        input.toDrawCriteria(RecommendationKind.SAFE).moodTags shouldBe setOf("轻松", "热血")
        input.toDrawCriteria(RecommendationKind.WILDCARD).moodTags shouldBe emptySet()
    }

    "勾选已完结开启期末保护（要求已完结）" {
        val input = RecommenderInput(
            time = TimeBudget.WEEKEND,
            acceptances = setOf(AcceptanceOption.NO_UNFINISHED),
        )
        input.toDrawCriteria(RecommendationKind.SAFE).finalsProtection.shouldBeTrue()
    }

    "excludeWorkIds 透传以保证三推荐互不重复" {
        val input = RecommenderInput(time = TimeBudget.WEEKEND)
        val c = input.toDrawCriteria(RecommendationKind.GAMBLE, exclude = setOf("w1", "w2"))
        c.excludeWorkIds shouldBe setOf("w1", "w2")
    }

    // endregion

    // region 卡片折叠（缺失即标记）

    "作品存在时折叠出标题与来源标签" {
        val rec = buildRecommendationUiModel(
            kind = RecommendationKind.SAFE,
            item = item("w1", dustDays = 3),
            work = work("w1"),
            drawReason = "测试理由。",
        )
        rec.workId shouldBe "w1"
        rec.card.title shouldBe "作品-w1"
        rec.card.sourceTags shouldContain "Bangumi"
        rec.card.backlogStatus shouldBe "吃灰 3 天"
    }

    "作品缺失时标题回退 workId、类型为暂无数据、评分为 null（绝不伪造）" {
        val rec = buildRecommendationUiModel(
            kind = RecommendationKind.WILDCARD,
            item = item("missing"),
            work = null,
            drawReason = "测试理由。",
        )
        rec.card.title shouldBe "missing"
        rec.card.type shouldBe "暂无数据"
        rec.card.ratingText.shouldBeNull()
    }

    "理由包含种类取向与抽番理由" {
        val rec = buildRecommendationUiModel(
            kind = RecommendationKind.SAFE,
            item = item("w1"),
            work = work("w1"),
            drawReason = "抽番理由。",
        )
        rec.reason shouldBe "${RecommendationKind.SAFE.tagline()}。抽番理由。"
    }

    // endregion
})
