package com.acgcompass.core.network.interceptor

import com.acgcompass.core.common.AppInfo
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 注入合规 User-Agent 的应用层拦截器。
 *
 * Bangumi 等源要求请求附带包含**应用名称与版本**的 User-Agent。本拦截器对所有出站请求统一
 * 注入形如 `Hoshimi/{versionName} (+{contact})` 的 UA；若上游已显式设置 UA，则尊重原值不覆盖。
 *
 * UA 字符串由构造参数传入，便于在 DI 层用 `BuildConfig.VERSION_NAME` 拼装，同时保持本类
 * **不依赖 Android**，可在 JVM 单元测试中直接验证（不硬编码任何密钥）。
 *
 * @property userAgent 完整 UA 字符串，使用 [defaultUserAgent] 构造。
 */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {

    init {
        require(userAgent.isNotBlank()) { "userAgent must not be blank" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // 尊重上游已设置的 UA（如某些源的特殊要求），仅在缺失时注入默认值。
        if (request.header(HEADER_USER_AGENT) != null) {
            return chain.proceed(request)
        }
        val tagged = request.newBuilder()
            .header(HEADER_USER_AGENT, userAgent)
            .build()
        return chain.proceed(tagged)
    }

    companion object {
        private const val HEADER_USER_AGENT = "User-Agent"

        /**
         * 默认联系/项目入口（不含任何私密信息）。：引用集中的 [AppInfo.UA_CONTACT]，
         * 与关于页 / 仓库地址同源，避免多处硬编码漂移。
         */
        const val DEFAULT_CONTACT: String = AppInfo.UA_CONTACT

        /**
         * 拼装合规 UA：`Hoshimi/{versionName} ({contact})`（产品令牌随应用更名）。
         *
         * @param versionName 应用版本名（通常取自 `BuildConfig.VERSION_NAME`）。
         * @param contact 联系/项目入口标识；默认 [DEFAULT_CONTACT]。
         */
        fun defaultUserAgent(versionName: String, contact: String = DEFAULT_CONTACT): String =
            "${AppInfo.APP_NAME}/$versionName ($contact)"
    }
}
