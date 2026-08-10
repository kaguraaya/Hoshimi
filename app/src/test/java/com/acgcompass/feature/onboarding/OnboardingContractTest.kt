package com.acgcompass.feature.onboarding

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 *
 * 覆盖纯 Kotlin 逻辑：是否展示引导的判定与默认文案的合规/隐私要点。
 * [OnboardingViewModel] 依赖 Android Context 的 [com.acgcompass.data.datastore.SettingsDataStore]，
 * 其端到端行为由插桩测试覆盖。
 */
class OnboardingContractTest : StringSpec({

    "未展示过引导时应展示（onboardingShown == false）" {
        shouldShowOnboarding(onboardingShown = false) shouldBe true
    }

    "已展示过引导时不再展示（onboardingShown == true）" {
        shouldShowOnboarding(onboardingShown = true) shouldBe false
    }

    "默认引导包含五条核心说明：不提供播放下载、本地保存、反代默认、多源开箱、AI 可选" {
        val state = OnboardingUiState.DEFAULT
        state.highlights shouldHaveSize 5

        val allText = (state.highlights.joinToString { it.title + it.description })
        allText shouldContain "播放"
        allText shouldContain "下载"
        allText shouldContain "本机"
        allText shouldContain "反代"
        allText shouldContain "API Key"
    }

    "默认引导提供主操作按钮文案" {
        OnboardingUiState.DEFAULT.confirmLabel shouldBe "开始使用"
    }

    "DeepSeek 默认使用 v4 flash 模型" {
        OnboardingDefaults.AI_MODEL shouldBe "deepseek-v4-flash"
    }
})
