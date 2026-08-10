package com.acgcompass.data.credential

/**
 * 单个数据源凭据的**非敏感元数据**。
 *
 * 仅承载状态信息，**绝不**包含任何明文凭据，可安全地经更高层镜像到 `CredentialMetaEntity`
 * 用于「我的 / 设置」页展示各源配置状态与最后测试时间。
 *
 * @property configured 是否已配置（加密存储中存在该源的凭据）。
 * @property lastTestedAt 最后一次连接测试时间戳（毫秒）；从未测试则为 `null`。
 * @property status 状态文案标识，见 [Status]。
 */
data class CredentialStatus(
    val configured: Boolean,
    val lastTestedAt: Long? = null,
    val status: String = if (configured) Status.CONFIGURED else Status.NOT_CONFIGURED,
) {
    /** 稳定的状态标识常量（避免散落的魔法字符串；更高层可据此本地化展示）。 */
    object Status {
        const val NOT_CONFIGURED = "not_configured"
        const val CONFIGURED = "configured"
        const val TEST_SUCCESS = "test_success"
        const val TEST_FAILED = "test_failed"
    }
}
