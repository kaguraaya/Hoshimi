package com.acgcompass.core.common

/**
 * 应用级公开元信息（仓库地址集中为**单一常量**，未来改名/迁移只改这一处）。
 *
 * 仅存放**可公开**的项目信息（应用名、作者、仓库地址、UA 联系入口），绝不含任何密钥 / token。
 * 关于页、User-Agent 等所有需要展示或上报仓库地址的地方都应引用这里，避免多处硬编码漂移。
 */
object AppInfo {

    /** 应用展示名（已更名为 Hoshimi）。与 res/values/strings.xml 的 app_name 保持一致。 */
    const val APP_NAME: String = "Hoshimi"

    /** 作者 / 维护者的 GitHub 用户名。 */
    const val AUTHOR: String = "kaguraaya"

    /** 项目 GitHub 仓库地址；这是全应用唯一的仓库地址来源。 */
    const val REPO_URL: String = "https://github.com/kaguraaya/Hoshimi"

    /**
     * 合规 User-Agent 的联系/项目入口标识（Bangumi 等要求 UA 携带可追溯的项目入口）。
     * 形如 `+https://github.com/...`，由 [REPO_URL] 派生，保证与仓库地址一致。
     */
    const val UA_CONTACT: String = "+$REPO_URL"
}
