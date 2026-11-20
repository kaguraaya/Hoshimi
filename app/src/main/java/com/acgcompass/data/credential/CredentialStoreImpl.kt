package com.acgcompass.data.credential

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.acgcompass.core.common.runCatchingCancellable
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * [CredentialStore] 的生产实现，基于 `EncryptedSharedPreferences`（AES256-GCM）+ Android Keystore
 * 保护的 [MasterKey]。
 *
 * 存储布局（均为密文落盘于专用加密 prefs 文件）：
 * - `secret_<SOURCE>`：序列化后的 [SecretBundle] JSON（明文仅在内存中存在，落盘加密）。
 * - `meta_tested_<SOURCE>`：最后测试时间戳（毫秒）。
 * - `meta_status_<SOURCE>`：状态标识（见 [CredentialStatus.Status]）。
 *
 * **安全不变式**：明文凭据绝不写入 Room / DataStore / 默认备份 / 日志；该加密 prefs 文件
 * 同时通过备份规则排除（见 `data_extraction_rules.xml` / `backup_rules.xml`）。元数据通过
 * [observeStatus] 发射，可由更高层镜像到 `CredentialMetaEntity`。
 */
@Singleton
class CredentialStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : CredentialStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 串行化所有读写，避免并发写入 EncryptedSharedPreferences 引发竞态。 */
    private val mutex = Mutex()

    private val statusFlow = MutableStateFlow<Map<SourceId, CredentialStatus>>(emptyMap())

    /**
     * 延迟初始化的加密 prefs。若 Keystore 主密钥 / 加密文件损坏（如设备凭据变更导致解密失败），
     * 删除损坏文件并重建，保证应用不崩溃；这等价于凭据失效，用户需重新配置。
     */
    private val prefs: SharedPreferences by lazy { createEncryptedPrefs() }

    init {
        // 初始即根据当前加密存储内容计算各源元数据快照。
        statusFlow.value = readAllStatus()
    }

    override suspend fun put(source: SourceId, secret: SecretBundle) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val payload = json.encodeToString(SecretBundle.serializer(), secret)
                prefs.edit()
                    .putString(secretKey(source), payload)
                    .putString(statusMetaKey(source), CredentialStatus.Status.CONFIGURED)
                    .apply()
                refreshStatus()
            }
        }
    }

    override suspend fun get(source: SourceId): SecretBundle? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val payload = prefs.getString(secretKey(source), null) ?: return@withLock null
            runCatchingCancellable { json.decodeFromString(SecretBundle.serializer(), payload) }
                .getOrNull()
        }
    }

    override suspend fun clear(source: SourceId) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                prefs.edit()
                    .remove(secretKey(source))
                    .remove(testedMetaKey(source))
                    .putString(statusMetaKey(source), CredentialStatus.Status.NOT_CONFIGURED)
                    .apply()
                refreshStatus()
            }
        }
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                prefs.edit().clear().apply()
                refreshStatus()
            }
        }
    }

    override fun observeStatus(): Flow<Map<SourceId, CredentialStatus>> = statusFlow.asStateFlow()

    override suspend fun exportRedacted(): RedactedCredentials = withContext(Dispatchers.IO) {
        mutex.withLock {
            val entries = SourceId.entries.mapNotNull { source ->
                val payload = prefs.getString(secretKey(source), null) ?: return@mapNotNull null
                val bundle = runCatchingCancellable {
                    json.decodeFromString(SecretBundle.serializer(), payload)
                }.getOrNull() ?: return@mapNotNull null
                source to bundle.toRedacted()
            }.toMap()
            RedactedCredentials(entries)
        }
    }

    /** 重新计算并发射全部源的元数据快照。必须在持有 [mutex] 时调用。 */
    private fun refreshStatus() {
        statusFlow.value = readAllStatus()
    }

    /** 从加密存储读取全部源的非敏感元数据（不解密明文，仅判断是否存在 + 读取状态/时间）。 */
    private fun readAllStatus(): Map<SourceId, CredentialStatus> = SourceId.entries.associateWith { source ->
        val configured = prefs.contains(secretKey(source))
        val lastTested = if (prefs.contains(testedMetaKey(source))) {
            prefs.getLong(testedMetaKey(source), 0L).takeIf { it > 0L }
        } else {
            null
        }
        val status = prefs.getString(statusMetaKey(source), null)
            ?: if (configured) CredentialStatus.Status.CONFIGURED else CredentialStatus.Status.NOT_CONFIGURED
        CredentialStatus(configured = configured, lastTestedAt = lastTested, status = status)
    }

    /** 创建（或在损坏时重建）AES256-GCM 加密的 SharedPreferences 实例。 */
    private fun createEncryptedPrefs(): SharedPreferences = runCatchingCancellable { buildEncryptedPrefs() }
        .getOrElse {
            // 加密层损坏：删除文件后重建，凭据视为失效（用户需重新配置）。
            context.deleteSharedPreferences(PREFS_FILE_NAME)
            buildEncryptedPrefs()
        }

    private fun buildEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun secretKey(source: SourceId): String = "secret_${source.name}"
    private fun statusMetaKey(source: SourceId): String = "meta_status_${source.name}"
    private fun testedMetaKey(source: SourceId): String = "meta_tested_${source.name}"

    companion object {
        /** 专用加密凭据文件名。需与备份排除规则中的 `sharedpref` 路径保持一致。 */
        const val PREFS_FILE_NAME = "acg_compass_credentials"
    }
}
