package com.acgcompass.domain.repository

import com.acgcompass.core.common.AppResult

/**
 * 备份 / 导出 / 导入仓库契约（领域层，纯 Kotlin）。
 *
 * 职责：
 * - [exportBackup]：把当前本地业务数据序列化为备份 JSON。**默认排除凭据**
 *   （`credentials=null`、`includesCredentials=false`）。
 * - [importBackup]：按业务主键合并导入数据，**合并不覆盖**——本地不存在则新增，冲突默认保留较新
 *   `updatedAt`，绝不覆盖更新的本地数据，并回报冲突明细。
 *
 * 凭据明文绝不进入备份；显式脱敏导出由更高层在二次确认后通过 [exportBackup] 的
 * [includeCredentials] 开关触发，导出值经掩码处理。
 *
 */
interface BackupRepository {

    /**
     * 序列化当前本地业务数据为备份 JSON 文本。
     *
     * @param includeCredentials 是否附带**脱敏**凭据。默认 `false`（零凭据）；为 `true` 时
     *   仅写入掩码值（如 `sk-****…ab`），绝不含完整明文。
     * @return 成功时为备份 JSON；失败时为 [AppResult.Failure]。
     */
    suspend fun exportBackup(includeCredentials: Boolean = false): AppResult<String>

    /**
     * 解析并合并导入备份 JSON（merge-not-overwrite）。
     *
     * @param json 备份 JSON 文本。
     * @return 成功时为 [ImportReport]（新增 / 更新计数与冲突明细）；JSON 损坏或写入失败时为
     *   [AppResult.Failure]。
     */
    suspend fun importBackup(json: String): AppResult<ImportReport>

    /** 和 [exportBackup] 相同，但直接写进 [output]（不在内存里拼出整段 JSON），调用方负责关闭流。 */
    suspend fun exportBackupTo(output: java.io.OutputStream, includeCredentials: Boolean = false): AppResult<Unit>

    /** 和 [importBackup] 相同，但直接从 [input] 解码，调用方负责关闭流。 */
    suspend fun importBackupFrom(input: java.io.InputStream): AppResult<ImportReport>

    /**
     * 导出指定数据表为 CSV 文本；不包含任何凭据。
     *
     * @param kind 要导出的数据类型。
     * @return 成功时为 CSV 文本；失败时为 [AppResult.Failure]。
     */
    suspend fun exportCsv(kind: CsvKind): AppResult<String>
}

/** CSV 导出的数据表种类。 */
enum class CsvKind { BACKLOG, RATINGS, RATED_WORKS, TIME_MACHINE, TASTE_PROFILE }

/**
 * 导入合并结果报告。
 *
 * @property added 新增行数（本地原本不存在的业务主键）。
 * @property updated 被导入数据更新的行数（导入数据严格更新于本地）。
 * @property conflictCount 主键冲突总数（默认已按「保留较新」解决）。
 * @property keptLocalCount 冲突里保留了本地数据的条数。
 * @property conflicts 冲突样例（最多几十条），供需要展示示例的地方使用；计数以上面两项为准。
 */
data class ImportReport(
    val added: Int,
    val updated: Int,
    val conflictCount: Int = 0,
    val keptLocalCount: Int = 0,
    val conflicts: List<ImportConflict> = emptyList(),
)

/**
 * 单条导入冲突（本地与导入存在同一业务主键）。
 *
 * @property table 业务表名。
 * @property key 业务主键。
 * @property keptLocal `true` 表示保留了本地数据（未被覆盖）；`false` 表示采用了更新的导入数据。
 */
data class ImportConflict(
    val table: String,
    val key: String,
    val keptLocal: Boolean,
)
