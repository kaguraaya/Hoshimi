package com.acgcompass.feature.discover

import com.acgcompass.domain.matching.MATCH_THRESHOLD
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 *
 * 覆盖纯 Kotlin 折叠逻辑：搜索结果 → 卡片映射 [toResultItem]（来源标签 + Match_Confidence）、
 * 低置信标记、手动纠正引用 [toSourceRef] 与置信度文案 [confidenceLabel]。界面行为由 Compose UI 测试覆盖。
 */
class DiscoverContractTest : StringSpec({

    fun work(id: String, mediaType: MediaType = MediaType.ANIME, year: Int? = null): Work = Work(
        id = id,
        titles = Titles(canonical = "作品-$id", ja = "ジャ-$id"),
        mediaType = mediaType,
        year = year,
        primarySource = SourceId.BANGUMI,
    )

    fun match(id: String, confidence: Float, source: SourceId = SourceId.BANGUMI): WorkMatch = WorkMatch(
        work = work(id),
        matchConfidence = confidence,
        sourceTag = source,
    )

    "高置信结果不标记低置信（>= 阈值）" {
        val item = match("a", MATCH_THRESHOLD.toFloat()).toResultItem()
        item.isLowConfidence shouldBe false
        item.workId shouldBe "a"
    }

    "低于阈值的结果标记为低置信（需手动确认）" {
        val item = match("b", (MATCH_THRESHOLD - 0.1).toFloat()).toResultItem()
        item.isLowConfidence shouldBe true
    }

    "来源标签 chips 同时包含数据来源与 Match_Confidence" {
        val item = match("c", 0.92f, source = SourceId.ANILIST).toResultItem()
        item.card.sourceTags shouldContain "AniList"
        item.card.sourceTags shouldContain "匹配度 92%"
    }

    "评分恒为 null（搜索结果不加载评分，暂无数据，不伪造）" {
        match("d", 0.9f).toResultItem().card.ratingText shouldBe null
    }

    "副标题拼接原名与年份" {
        val item = WorkMatch(
            work = work("e", year = 2023),
            matchConfidence = 0.9f,
            sourceTag = SourceId.BANGUMI,
        ).toResultItem()
        item.card.subtitle shouldBe "ジャ-e · 2023"
    }

    "手动纠正引用置 userOverridden=true 并保留来源与置信度" {
        val item = match("f", 0.4f, source = SourceId.VNDB).toResultItem()
        val ref = item.toSourceRef()
        ref.userOverridden shouldBe true
        ref.sourceId shouldBe SourceId.VNDB
        ref.sourceItemId shouldBe "f"
        ref.matchConfidence shouldBe 0.4f
    }

    "置信度文案四舍五入到整数百分比并夹取到 [0,1]" {
        confidenceLabel(0.857f) shouldBe "匹配度 86%"
        confidenceLabel(0f) shouldBe "匹配度 0%"
        confidenceLabel(1f) shouldBe "匹配度 100%"
        confidenceLabel(1.5f) shouldBe "匹配度 100%"
    }
})
