package com.acgcompass.domain.taste

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * 社区分按评分人数收缩：一两个人打出的极端分不能把预计评分抬到天上（例如只有 1 人打 10 分的条目）；
 * 几千人评分的条目基本不变；评分人数未知时不收缩。
 */
class CommunityShrinkTest : StringSpec({
    val prior = PersonalDivergenceModel.COMMUNITY_VOTE_PRIOR

    "few votes pull the score toward the site mean, many votes leave it alone" {
        val one = PersonalDivergenceModel.shrinkCommunity(10.0, 1)!!
        one shouldBeLessThan 8.0
        PersonalDivergenceModel.shrinkCommunity(8.0, 5000)!! shouldBe (8.0 plusOrMinus 0.01)
        PersonalDivergenceModel.shrinkCommunity(8.0, 0) shouldBe 8.0
        PersonalDivergenceModel.shrinkCommunity(null, 100) shouldBe null
        PersonalDivergenceModel.shrinkCommunity(9.0, 3, prior = 0.0) shouldBe 9.0
    }

    "a one-vote 10.0 title is not predicted far above the user's usual score" {
        val r = Random(3)
        val samples = (0 until 60).map { i ->
            val community = 6.0f + r.nextInt(25) / 10f
            TasteSample(
                "s$i",
                community.toInt().coerceIn(1, 10),
                null,
                1_000L * i,
                WorkFeature(
                    subjectId = "s$i",
                    tagCounts = listOf(TagCount("日常", 50), TagCount("校园", 40)),
                    bangumiScore = community,
                    bangumiVotes = 3000,
                ),
            )
        }
        val obscure = WorkFeature(
            subjectId = "x",
            tagCounts = listOf(TagCount("日常", 50)),
            bangumiScore = 10.0f,
            bangumiVotes = 1,
        )
        val shrunk = PersonalDivergenceModel.fit(samples)!!.predict(obscure)
        val raw = PersonalDivergenceModel.fit(samples, communityVotePrior = 0.0)!!.predict(obscure)
        shrunk.rating shouldBeLessThan raw.rating - 1.0
        shrunk.rating shouldBeLessThan shrunk.userMean + 1.0
        // 展示用的社区分保持原值，只有基线收缩。
        shrunk.community shouldBe 10.0
    }
})
