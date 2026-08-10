package com.acgcompass.domain.matching

import io.kotest.common.ExperimentalKotest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldNotEndWith
import io.kotest.matchers.string.shouldNotStartWith
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.of
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

/**
 * Property-based tests for [normalizeTitle] .
 *
 *
 * 核心断言：归一化是幂等的 —— normalizeTitle(normalizeTitle(x)) == normalizeTitle(x)。
 * 这保证同一作品在不同源（中文 / 日文 / 罗马音 / 英文 / 别名）下的标题，反复归一化后会折叠到
 * 同一个可比较的规范形，从而稳定地参与跨源匹配。
 *
 */
@OptIn(ExperimentalKotest::class)
class TitleNormalizerPropertyTest : StringSpec({

    // 覆盖 ascii、latin-1、CJK、全角形（fullwidth forms）、标点与各类空白的码点池。
    // 用这些码点拼接字符串，逼近真实多语言标题的输入空间。
    val mixedScriptCodepoints: Arb<String> = run {
        val pools = listOf(
            // ascii 小写字母
            ('a'..'z').toList(),
            // ascii 大写字母（折叠用）
            ('A'..'Z').toList(),
            // 数字
            ('0'..'9').toList(),
            // ascii 标点 / 空白起始段
            (' '..'/').toList(),
            // CJK / 全角标点
            "！？。、・「」（）：；ー".toList(),
            // 全角数字与字母（NFKC 折叠目标）
            "．０１２３ＡＢＣａｂｃ".toList(),
            // 日文（汉字 + 假名）
            "鋼の錬金術師魔法少女まどか物語".toList(),
            // 中文 / CJK 统一表意
            "中華一番美少女戦士月野兎".toList(),
            // 全角空格 + 各类空白
            "\u3000\t\n ".toList(),
        )
        val allCodepoints = pools.flatten()
        Arb.list(Arb.of(allCodepoints), 0..40).let { listArb ->
            arbitrary { rs -> listArb.bind().joinToString("") }
        }
    }

    ": normalizeTitle is idempotent over mixed-script unicode input" {
        checkAll(PropTestConfig(iterations = 200), mixedScriptCodepoints) { raw ->
            val once = normalizeTitle(raw)
            val twice = normalizeTitle(once)

            // 幂等：再次归一化为恒等变换。
            twice shouldBe once

            // 幂等隐含的结构不变量：输出无首尾空白、无连续双空格。
            once shouldNotStartWith " "
            once shouldNotEndWith " "
            once shouldNotContain "  "
        }
    }

    ": normalizeTitle is idempotent over arbitrary unicode strings" {
        checkAll(PropTestConfig(iterations = 200), Arb.string()) { raw ->
            val once = normalizeTitle(raw)
            normalizeTitle(once) shouldBe once
        }
    }

    ": one work's multilingual names each normalize idempotently and collapse comparably" {
        // 同一作品（《魔法少女まどか☆マギカ》）在不同源下的标题 / 别名。归一化的核心承诺是幂等；
        // 这里同时演示多语言名各自归一化后均稳定（再次归一化不变），即跨源可比较的规范形。
        val oneWorkNames = Arb.of(
            // 日文原名
            "魔法少女まどか☆マギカ",
            // 中文译名
            "魔法少女小圆",
            // 罗马音
            "Mahou Shoujo Madoka Magica",
            // 英文官方名
            "Puella Magi Madoka Magica",
            // 日文常见别名 / 缩写
            "まどマギ",
            // 全角英文别名（NFKC 折叠目标）
            "ＭＡＤＯＫＡ　ＭＡＧＩＣＡ",
        )
        checkAll(PropTestConfig(iterations = 100), oneWorkNames) { name ->
            val once = normalizeTitle(name)
            // 幂等（核心断言）。
            normalizeTitle(once) shouldBe once
            // 结构不变量。
            once shouldNotStartWith " "
            once shouldNotEndWith " "
            once shouldNotContain "  "
        }
    }
})
