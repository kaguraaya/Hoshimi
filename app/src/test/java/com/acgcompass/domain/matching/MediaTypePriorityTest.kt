package com.acgcompass.domain.matching

import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.model.WorkMatch
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * 导入类型优先级测试，验证输入动画名称时不会默认加入漫画版本。
 *
 * 仅覆盖纯函数排序与歧义判定逻辑（[sortMatchesByTypePriority] / [hasAmbiguousMediaType] /
 * [mediaTypePriority]），不触网、不依赖 Android。
 */
class MediaTypePriorityTest : StringSpec({

    fun work(id: String, type: MediaType, title: String = "9-nine-"): Work = Work(
        id = id,
        titles = Titles(canonical = title),
        mediaType = type,
        primarySource = SourceId.BANGUMI,
    )

    fun match(id: String, type: MediaType, confidence: Float): WorkMatch =
        WorkMatch(work = work(id, type), matchConfidence = confidence, sourceTag = SourceId.BANGUMI)

    "媒介优先级：动画 < 游戏/VN < 漫画/小说" {
        mediaTypePriority(MediaType.ANIME) shouldBe 0
        mediaTypePriority(MediaType.GAME) shouldBe 1
        mediaTypePriority(MediaType.VN) shouldBe 1
        mediaTypePriority(MediaType.MANGA) shouldBe 2
        mediaTypePriority(MediaType.NOVEL) shouldBe 2
    }

    "同名同置信度：番剧名不会默认落到漫画版本（动画排首位）" {
        // 漫画候选先出现，但置信度与动画相同——排序后动画应排在最前，避免静默加入漫画。
        val matches = listOf(
            match("manga", MediaType.MANGA, 0.9f),
            match("anime", MediaType.ANIME, 0.9f),
            match("novel", MediaType.NOVEL, 0.9f),
        )
        val sorted = sortMatchesByTypePriority(matches)
        sorted.first().work.mediaType shouldBe MediaType.ANIME
        sorted.first().work.id shouldBe "anime"
    }

    "置信度显著更高时仍以置信度为准（不强行把低置信动画顶上去）" {
        val matches = listOf(
            match("manga", MediaType.MANGA, 0.95f),
            match("anime", MediaType.ANIME, 0.50f),
        )
        val sorted = sortMatchesByTypePriority(matches)
        sorted.first().work.id shouldBe "manga"
    }

    "同名多类型且置信接近 → 判定为歧义（应弹出选择）" {
        val matches = listOf(
            match("anime", MediaType.ANIME, 0.90f),
            match("manga", MediaType.MANGA, 0.88f),
        )
        hasAmbiguousMediaType(matches) shouldBe true
    }

    "仅单一类型 → 非歧义（无需弹出选择）" {
        val matches = listOf(
            match("anime", MediaType.ANIME, 0.90f),
            match("anime2", MediaType.ANIME, 0.88f),
        )
        hasAmbiguousMediaType(matches) shouldBe false
    }

    "类型不同但置信度差距大 → 非歧义（按置信度决定即可）" {
        val matches = listOf(
            match("anime", MediaType.ANIME, 0.90f),
            match("manga", MediaType.MANGA, 0.50f),
        )
        hasAmbiguousMediaType(matches) shouldBe false
    }
})
