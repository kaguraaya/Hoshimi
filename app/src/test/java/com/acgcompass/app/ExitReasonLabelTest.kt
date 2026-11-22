package com.acgcompass.app

import android.app.ApplicationExitInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** 只记值得排查的系统退出原因；自己退出、用户结束、升级等正常情况不记。 */
class ExitReasonLabelTest : StringSpec({
    "crashes, ANRs and system kills are recorded" {
        exitReasonLabel(ApplicationExitInfo.REASON_CRASH) shouldBe "闪退"
        exitReasonLabel(ApplicationExitInfo.REASON_CRASH_NATIVE) shouldBe "闪退（原生代码）"
        exitReasonLabel(ApplicationExitInfo.REASON_ANR) shouldBe "无响应（ANR）"
        exitReasonLabel(ApplicationExitInfo.REASON_LOW_MEMORY) shouldBe "内存不足被系统结束"
    }

    "normal exits are not recorded" {
        exitReasonLabel(ApplicationExitInfo.REASON_EXIT_SELF) shouldBe null
        exitReasonLabel(ApplicationExitInfo.REASON_USER_REQUESTED) shouldBe null
        exitReasonLabel(ApplicationExitInfo.REASON_USER_STOPPED) shouldBe null
        exitReasonLabel(ApplicationExitInfo.REASON_PERMISSION_CHANGE) shouldBe null
    }
})
