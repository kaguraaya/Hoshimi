package com.acgcompass.feature.detail

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.taste.AdvancedTasteProfile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** 口味模型只用动画建立：游戏、书籍等作品的详情页不给匹配度和预计评分。 */
class DetailTasteMediaTest : StringSpec({
    fun work(mediaType: MediaType) = Work(
        id = "w",
        titles = Titles(canonical = "作品"),
        mediaType = mediaType,
        primarySource = SourceId.BANGUMI,
    )

    "non-anime works get no taste match" {
        for (type in listOf(MediaType.GAME, MediaType.MANGA, MediaType.NOVEL, MediaType.VN, MediaType.OTHER)) {
            val profile = AdvancedTasteProfile(generatedAt = 0L)
            val ui = work(type).toTasteMatch(advancedMatch = null, advancedProfile = profile)
            ui.shouldBeInstanceOf<TasteMatchUiModel.Unavailable>()
            ui.message shouldBe "口味匹配度和预计评分只针对动画"
        }
    }

    "anime keeps the usual reasons" {
        val ui = work(MediaType.ANIME).toTasteMatch(advancedMatch = null, advancedProfile = null)
        ui.shouldBeInstanceOf<TasteMatchUiModel.Unavailable>()
        (ui.message == "口味匹配度和预计评分只针对动画") shouldBe false
    }
})
