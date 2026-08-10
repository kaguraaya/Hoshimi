package com.acgcompass.domain.usecase

import com.acgcompass.domain.usecase.TasteTagTaxonomy.TagDimension
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * [TasteTagTaxonomy] 多维分类与规则覆盖单元测试。
 *
 * 覆盖：四维度分类（META/STUDIO/DEMOGRAPHIC/CONTENT）、权重单调性、日期正则新增变体、
 * 噪声/厂商/受众池完整性，以及「百合等强题材不被误判为受众」的边界。
 */
class TasteTagTaxonomyTest : StringSpec({

    "维度分类：题材/受众/厂商/元数据各归其位" {
        TasteTagTaxonomy.dimensionOf("魔法") shouldBe TagDimension.CONTENT
        TasteTagTaxonomy.dimensionOf("少年向") shouldBe TagDimension.DEMOGRAPHIC
        TasteTagTaxonomy.dimensionOf("京都动画") shouldBe TagDimension.STUDIO
        TasteTagTaxonomy.dimensionOf("2024年10月") shouldBe TagDimension.META
    }

    "权重单调性：元数据 < 厂商 < 受众 < 题材" {
        val meta = TasteTagTaxonomy.weightFactor("2024年")
        val studio = TasteTagTaxonomy.weightFactor("飞碟社")
        val demo = TasteTagTaxonomy.weightFactor("青年向")
        val content = TasteTagTaxonomy.weightFactor("悬疑")
        studio shouldBeGreaterThan meta
        demo shouldBeGreaterThan studio
        content shouldBeGreaterThan demo
        content shouldBe TasteTagTaxonomy.CONTENT_FACTOR
    }

    "weightFactor 等于其维度因子（多维度一致性）" {
        TasteTagTaxonomy.weightFactor("男性向") shouldBe TagDimension.DEMOGRAPHIC.factor
        TasteTagTaxonomy.weightFactor("madhouse") shouldBe TagDimension.STUDIO.factor
        TasteTagTaxonomy.weightFactor("科幻") shouldBe TagDimension.CONTENT.factor
        TasteTagTaxonomy.weightFactor("2024") shouldBe TagDimension.META.factor
    }

    "日期正则池完整性：覆盖季度/新番/Q 季度等变体" {
        listOf("2024", "2024年", "2024年10月", "2024-10", "10月", "2024春", "2024年春季", "10月新番", "2024Q1")
            .forEach { TasteTagTaxonomy.isMeta(it) shouldBe true }
    }

    "噪声池完整性：放送状态/关联/地区被识别为元数据" {
        listOf("连载中", "完结", "续作", "总集篇", "番外", "欧美", "台湾", "网络动画", "泡面番")
            .forEach { TasteTagTaxonomy.isMeta(it) shouldBe true }
    }

    "厂商池完整性：常见厂牌与别名命中（大小写不敏感）" {
        listOf("MAPPA", "ufotable", "CloverWorks", "京都动画", "扳机社", "东映动画")
            .forEach { TasteTagTaxonomy.isStudio(it) shouldBe true }
    }

    "边界：百合等强题材不被误判为受众，保持全权重" {
        TasteTagTaxonomy.isDemographic("百合") shouldBe false
        TasteTagTaxonomy.dimensionOf("百合") shouldBe TagDimension.CONTENT
        TasteTagTaxonomy.weightFactor("百合") shouldBe TasteTagTaxonomy.CONTENT_FACTOR
    }

    "题材白名单保留题材并剔除人物名厂商元数据等噪声" {
        listOf("战斗", "奇幻", "科幻", "百合", "悬疑", "时间旅行", "治愈", "BL")
            .forEach { TasteTagTaxonomy.isSelectableGenre(it) shouldBe true }
        listOf("牧濑红莉栖", "爱的战士虚渊玄", "京都动画", "madhouse", "2024年10月", "TV", "少年向", "续作", "")
            .forEach { TasteTagTaxonomy.isSelectableGenre(it) shouldBe false }
    }

    "题材白名单：清洗（下划线/空白/大小写）后仍能命中" {
        TasteTagTaxonomy.isSelectableGenre("  科幻 ") shouldBe true
        TasteTagTaxonomy.isSelectableGenre("bl") shouldBe true
        TasteTagTaxonomy.isSelectableGenre("Bl") shouldBe true
    }

    "空白标签视为元数据（最弱权重，构建画像时被过滤）" {
        TasteTagTaxonomy.isMeta("") shouldBe true
        TasteTagTaxonomy.isMeta("   ") shouldBe true
    }
})
