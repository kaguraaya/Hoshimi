package com.acgcompass.data.credential

import kotlinx.coroutines.flow.Flow

/**
 * 凭据存储契约：本地、加密、按来源隔离。
 *
 * 实现基于 `EncryptedSharedPreferences`（AES256-GCM，MasterKey 由 Android Keystore 保护）。
 *
 * **安全不变式（CRITICAL）**：
 * - 明文凭据**绝不**写入 Room、DataStore、默认备份或日志。
 * - 仅本接口的 [get] 在内存中返回明文；[observeStatus] 与 [exportRedacted] 一律仅暴露
 *   非敏感元数据或脱敏值。
 * - 状态元数据可由更高层镜像到 `CredentialMetaEntity`；本存储只负责加密读写与元数据发射。
 */
interface CredentialStore {

    /**
     * 加密写入指定源的凭据（覆盖既有值）。写入后刷新 [observeStatus] 元数据。
     *
     * @param source 凭据归属源。
     * @param secret 明文凭据集合（落盘前由实现加密）。
     */
    suspend fun put(source: SourceId, secret: SecretBundle)

    /**
     * 读取并解密指定源的凭据。
     *
     * @return 明文 [SecretBundle]；未配置或解密失败时返回 `null`。调用方**不得**将其写入日志 / Room / 备份。
     */
    suspend fun get(source: SourceId): SecretBundle?

    /**
     * 清除指定源的凭据，并将其元数据状态更新为「未配置」。
     */
    suspend fun clear(source: SourceId)

    /** Remove every locally stored credential and its non-sensitive status metadata. */
    suspend fun clearAll()

    /**
     * 观察所有源的凭据**元数据**（是否配置 / 最后测试时间 / 状态）。
     *
     * 发射的内容**不含**任何明文凭据，可安全用于 UI 状态展示。
     */
    fun observeStatus(): Flow<Map<SourceId, CredentialStatus>>

    /**
     * 导出**脱敏**后的凭据集合（敏感字段掩码，如 `sk-****…ab`）。
     *
     * 默认备份不含凭据；仅当用户显式选择导出并二次确认时调用。输出不含任何完整明文。
     */
    suspend fun exportRedacted(): RedactedCredentials
}
