package com.acgcompass.feature.settings

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SettingsContractTest : StringSpec({

    "fresh AI settings default to DeepSeek v4 flash" {
        AiProviderCardState().selectedProvider shouldBe AiProviderOption.DEEPSEEK
        AiProviderOption.DEEPSEEK.defaultModel shouldBe "deepseek-v4-flash"
    }
})
