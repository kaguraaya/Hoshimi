package com.acgcompass.domain.taste

import com.acgcompass.data.taste.BuiltInTasteAlgorithmConfigSource
import com.acgcompass.domain.taste.TasteAlgorithmConfigSource.ImportResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

class TasteAlgorithmConfigTest : StringSpec({

    "the built-in algorithm passes validation and round-trips through the template" {
        val config = TasteAlgorithmConfig.DEFAULT
        config.validate().shouldBeEmpty()
        TasteAlgorithmConfig.decode(TasteAlgorithmConfig.encode(config)) shouldBe config
        TasteMatchBranch.entries.forEach { config.branches.containsKey(it.key) shouldBe true }
    }

    "the template lists every tunable scoring parameter with its current value" {
        val scoring = TasteAlgorithmConfig.DEFAULT.scoring
        scoring["PREF_SLOPE"] shouldBe TasteScoringParams.PREF_SLOPE
        scoring["MAX_PROFILE_SAMPLES"] shouldBe TasteScoringParams.MAX_PROFILE_SAMPLES.toDouble()
        (scoring.size >= 60) shouldBe true
        TasteAlgorithmConfig.DEFAULT.divergence.personalGain shouldBe PersonalDivergenceModel.PERSONAL_GAIN
        TasteAlgorithmConfig.DEFAULT.verdict.likelyLove shouldBe TasteVerdict.LIKELY_LOVE
    }

    "the template file in the repository matches the code" {
        val file = File("../docs/algorithm-template.json")
        file.readText().trim() shouldBe TasteAlgorithmConfig.encode(TasteAlgorithmConfig.DEFAULT).trim()
    }

    "importing is validated but not applied yet" {
        val source = BuiltInTasteAlgorithmConfigSource()
        source.importReplacement("not json").shouldBeInstanceOf<ImportResult.Invalid>()
        val broken = TasteAlgorithmConfig.DEFAULT.copy(branches = emptyMap())
        val result = source.importReplacement(TasteAlgorithmConfig.encode(broken))
        result.shouldBeInstanceOf<ImportResult.Invalid>()
        source.importReplacement(source.exportTemplate()) shouldBe ImportResult.NotAvailable
        source.current() shouldBe TasteAlgorithmConfig.DEFAULT
    }
})
