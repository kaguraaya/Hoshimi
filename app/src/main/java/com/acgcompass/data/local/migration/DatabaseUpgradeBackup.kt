package com.acgcompass.data.local.migration

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.acgcompass.data.local.AcgCompassDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一份升级前备份的元数据。[counts] 是升级前几张核心表的行数，升级后用来自检。 */
@Serializable
data class UpgradeBackupMeta(
    val fromVersion: Int,
    val toVersion: Int,
    val createdAt: Long,
    val bytes: Long,
    val counts: Map<String, Long> = emptyMap(),
)

/** 磁盘上的一份升级前备份。 */
data class UpgradeBackup(val dir: File, val meta: UpgradeBackupMeta)

/**
 * 数据库升级前备份：在 Room 打开数据库**之前**读数据库文件自己的版本号（`PRAGMA user_version`），
 * 比当前版本低就把数据库文件（连同 `-wal`、`-shm`）原样复制一份。不依赖任何「上次版本」记录，
 * 所以从任何老版本升级上来都能识别。
 *
 * 备份放在应用私有目录 `files/db-backups/`，最多保留 [KEEP] 份；空间不足时跳过（不阻塞启动）。
 * 复制完写一个「待自检」标记，[DatabaseHealthMonitor] 在升级后对比核心表行数，确认没有丢数据。
 */
object DatabaseUpgradeBackup {
    private const val DIR = "db-backups"
    private const val META = "meta.json"
    private const val PENDING = "pending-verify"
    private const val KEEP = 2
    private const val SPACE_FACTOR = 3
    private val JSON = Json { ignoreUnknownKeys = true }

    /** 升级后自检对比的表。 */
    val CORE_TABLES: List<String> = listOf("user_collections", "backlog_items", "ratings", "snapshots", "works")

    /**
     * 需要升级时备份，返回备份目录；不需要（全新安装、已是最新、版本读不出）或空间不足时返回 null。
     * 必须在 Room 打开数据库之前调用。
     */
    fun backupIfUpgrading(
        context: Context,
        targetVersion: Int = DatabasePreMigrationBackup.CURRENT_DB_VERSION,
        now: Long = System.currentTimeMillis(),
    ): File? {
        val db = context.getDatabasePath(AcgCompassDatabase.DATABASE_NAME)
        val version = db.takeIf { it.isFile }?.let(::readUserVersion)
        if (version == null || version <= 0 || version >= targetVersion) return null
        val files = listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).filter { it.isFile }
        val bytes = files.sumOf { it.length() }
        val root = File(context.filesDir, DIR).apply { mkdirs() }
        return if (root.usableSpace < bytes * SPACE_FACTOR) {
            null
        } else {
            copyInto(
                root,
                db,
                files,
                version,
                targetVersion,
                now,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun copyInto(root: File, db: File, files: List<File>, version: Int, targetVersion: Int, now: Long): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(now))
        val dir = File(root, "v$version-$stamp").apply { mkdirs() }
        return runCatching {
            files.forEach { it.copyTo(File(dir, it.name), overwrite = true) }
            val meta = UpgradeBackupMeta(version, targetVersion, now, files.sumOf { it.length() }, countRows(db))
            File(dir, META).writeText(JSON.encodeToString(UpgradeBackupMeta.serializer(), meta))
            File(root, PENDING).writeText(dir.name)
            prune(root)
            dir
        }.getOrElse {
            dir.deleteRecursively()
            null
        }
    }

    /** 全部升级前备份，最新的在前。 */
    fun list(context: Context): List<UpgradeBackup> {
        val root = File(context.filesDir, DIR)
        return root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { dir -> readMeta(dir)?.let { UpgradeBackup(dir, it) } }
            .sortedByDescending { it.meta.createdAt }
    }

    /** 等待升级后自检的那份备份。 */
    fun pendingVerification(context: Context): UpgradeBackup? {
        val root = File(context.filesDir, DIR)
        val name = File(root, PENDING).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        if (name.isEmpty()) return null
        val dir = File(root, name)
        return readMeta(dir)?.let { UpgradeBackup(dir, it) }
    }

    fun clearPendingVerification(context: Context) {
        File(File(context.filesDir, DIR), PENDING).delete()
    }

    /**
     * 用升级前备份覆盖当前数据库文件（调用方随后必须重启进程，且不能有打开的连接）。
     */
    fun restore(context: Context, backup: UpgradeBackup) {
        val db = context.getDatabasePath(AcgCompassDatabase.DATABASE_NAME)
        listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).forEach { it.delete() }
        backup.dir.listFiles().orEmpty()
            .filter { it.name.startsWith(AcgCompassDatabase.DATABASE_NAME) }
            .forEach { it.copyTo(File(db.parentFile, it.name), overwrite = true) }
    }

    /** 读数据库文件的 schema 版本；读不出返回 null。 */
    internal fun readUserVersion(file: File): Int? = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
    }.getOrNull()

    /** 核心表的行数（表不存在的跳过）。 */
    internal fun countRows(file: File): Map<String, Long> = runCatching {
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            CORE_TABLES.mapNotNull { table ->
                runCatching {
                    db.rawQuery(
                        "SELECT COUNT(*) FROM `$table`",
                        null,
                    ).use { c -> if (c.moveToFirst()) table to c.getLong(0) else null }
                }.getOrNull()
            }.toMap()
        }
    }.getOrDefault(emptyMap())

    private fun readMeta(dir: File): UpgradeBackupMeta? = runCatching {
        JSON.decodeFromString(UpgradeBackupMeta.serializer(), File(dir, META).readText())
    }.getOrNull()

    private fun prune(root: File) {
        root.listFiles { f -> f.isDirectory }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .forEach { it.deleteRecursively() }
    }
}
