package com.acgcompass.domain.recommend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class SessionFitTest : StringSpec({

    "20 minutes favours short or episodic shows over serial dramas" {
        val gag = SessionFit.evaluate(20, 12, 24, listOf("搞笑", "日常", "校园"))
        val mystery = SessionFit.evaluate(20, 12, 24, listOf("悬疑", "推理", "剧情"))
        val shortAnime = SessionFit.evaluate(20, 12, 3, listOf("恋爱"))

        gag.kind shouldBe SessionFit.Kind.EPISODIC
        mystery.kind shouldBe SessionFit.Kind.START
        shortAnime.kind shouldBe SessionFit.Kind.EPISODIC
        gag.score shouldBeGreaterThan mystery.score
    }

    "a whole short series that fits the session is the best fit" {
        val finish = SessionFit.evaluate(60, 12, 4, listOf("剧情"))
        finish.kind shouldBe SessionFit.Kind.FINISH
        finish.score shouldBeGreaterThan SessionFit.evaluate(60, 12, 24, listOf("日常")).score
    }

    "a film is marked as a movie when it fits" {
        SessionFit.evaluate(180, 1, 110, emptyList()).kind shouldBe SessionFit.Kind.MOVIE
        SessionFit.evaluate(20, 1, 110, emptyList()).kind shouldBe SessionFit.Kind.TOO_LONG
    }

    "missing runtime stays neutral" {
        SessionFit.evaluate(20, null, null, listOf("日常")).kind shouldBe SessionFit.Kind.UNKNOWN
    }

    "short sessions weigh time fit more" {
        SessionFit.weight(20) shouldBeGreaterThan SessionFit.weight(null)
    }
})
