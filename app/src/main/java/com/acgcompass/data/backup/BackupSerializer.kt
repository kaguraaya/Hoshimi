package com.acgcompass.data.backup

import kotlinx.serialization.json.Json

/**
 * 纯函数式备份序列化器（领域无关、无 Android / DAO 依赖，可在 JVM 单测中直接调用）。
 *
 * 提供一对互逆的纯函数：
 * - [serialize]：把 [BackupEnvelope] 编码为备份 JSON 文本。
 * - [deserialize]：把备份 JSON 文本解码回 [BackupEnvelope]。
 *
 * **Round-trip 不变式**：对任意不含凭据的业务数据信封 `x`，
 * `deserialize(serialize(x))` 还原出与 `x` 业务等价的信封（各列表字段全部保留且等价）。
 *
 * **默认零凭据**：当 `x.credentials == null` 时，输出 JSON 的
 * `credentials` 字段为 `null`、`includesCredentials` 为 `false`，不含任何凭据明文。
 */
object BackupSerializer {

    /**
     * 共享的 [Json] 配置：
     * - `encodeDefaults = true`：默认值字段也写出，保证 schema 完整、round-trip 稳定。
     * - `ignoreUnknownKeys = true`：跨版本前向兼容（新增字段不会让旧版本解析失败）。
     * - `prettyPrint = true`：导出文件人类可读。
     * - 不允许特殊浮点值（NaN/Infinity）——默认即关闭，避免产生非法 JSON。
     */
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** 把备份信封编码为 JSON 文本（纯函数）。 */
    fun serialize(envelope: BackupEnvelope): String = json.encodeToString(BackupEnvelope.serializer(), envelope)

    /**
     * 把备份 JSON 文本解码为 [BackupEnvelope]（纯函数）。
     *
     * @throws kotlinx.serialization.SerializationException JSON 损坏或不符合 schema 时抛出；
     *   由更高层（`BackupRepositoryImpl.importBackup`）经 `runCatchingApp` 兜底为 `AppError`。
     */
    fun deserialize(json: String): BackupEnvelope = this.json.decodeFromString(BackupEnvelope.serializer(), json)
}
