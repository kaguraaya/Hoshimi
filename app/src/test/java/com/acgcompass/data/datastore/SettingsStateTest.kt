package com.acgcompass.data.datastore

import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * 单元测试：[SettingsState] 默认值与 [ThemeMode] 持久化解析。
 *
 * 这些是纯 Kotlin 逻辑；[SettingsDataStore] 本身依赖 Android Context，需在 instrumented 测试中覆盖。
 */
@OptIn(ExperimentalKotest::class)
class SettingsStateTest : StringSpec({

    "默认设置为隐私安全取向：AI 分析与成人内容默认关闭、引导未展示" {
        val state = SettingsState()
        state.allowAiAnalyzeReviews shouldBe false
        state.showAdultContent shouldBe false
        state.onboardingShown shouldBe false
    }

    "默认启用 Bangumi、AniList、Jikan 和 VNDB，MAL 需手动开启" {
        val state = SettingsState()
        state.bangumiEnabled shouldBe true
        state.anilistEnabled shouldBe true
        state.jikanEnabled shouldBe true
        state.malEnabled shouldBe false
        state.vndbEnabled shouldBe true
    }

    "默认主题为跟随系统并启用动态取色，默认记录时光机快照" {
        val state = SettingsState()
        state.themeMode shouldBe ThemeMode.SYSTEM
        state.dynamicColor shouldBe true
        state.recordTimeMachineSnapshots shouldBe true
    }

    "ThemeMode.fromStorage 对未知或空值回退为 SYSTEM（升级/损坏数据不崩溃）" {
        ThemeMode.fromStorage(null) shouldBe ThemeMode.SYSTEM
        ThemeMode.fromStorage("") shouldBe ThemeMode.SYSTEM
        ThemeMode.fromStorage("not-a-mode") shouldBe ThemeMode.SYSTEM
    }

    "ThemeMode 序列化 round-trip 还原原值（大小写不敏感解析）" {
        // ThemeMode 仅 3 个取值，少量迭代即可充分覆盖，加快测试速度。
        checkAll(PropTestConfig(iterations = 20), Arb.enum<ThemeMode>()) { mode ->
            ThemeMode.fromStorage(mode.toStorage()) shouldBe mode
            ThemeMode.fromStorage(mode.toStorage().lowercase()) shouldBe mode
        }
    }

    "任意字符串解析均不抛异常且落在合法枚举内" {
        checkAll(PropTestConfig(iterations = 30), Arb.string()) { raw ->
            val parsed = ThemeMode.fromStorage(raw)
            (parsed in ThemeMode.entries) shouldBe true
        }
    }
})
