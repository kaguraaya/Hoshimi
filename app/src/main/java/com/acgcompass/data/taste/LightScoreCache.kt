package com.acgcompass.data.taste

import android.content.Context
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.domain.taste.LightScore
import com.acgcompass.domain.taste.WorkFeature
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 轻量分（[LightScore]）的缓存：内存一份，本地文件一份。
 *
 * 键是「画像输入指纹 + 当前 AI 标签分类」（[com.acgcompass.domain.taste.TasteInputsKey]）：指纹相同，同一部作品
 * （资料更新时间也相同）的分数就一定相同，所以重启应用后可以直接拿上次的分，不用把整个作品库重算一遍。
 * 指纹一变就整张表作废；单部作品的资料更新过（更新时间对不上）就只重算这一部。
 */
@Singleton
class LightScoreCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var memoryKey: String? = null
    private val memory = ConcurrentHashMap<String, Entry>()

    /** 按 [key] 找这些作品的分；资料更新时间对不上的不算命中。第一次用某个键时会读一次本地文件。 */
    @Synchronized
    fun lookup(key: String, features: Map<String, WorkFeature>): Map<String, LightScore> {
        if (memoryKey != key) {
            memory.clear()
            memoryKey = key
            readDisk(key)?.let { memory.putAll(it) }
        }
        return features.mapNotNull { (id, f) ->
            memory[id]?.takeIf { it.updatedAt == f.updatedAt }?.let { id to it.toScore() }
        }.toMap()
    }

    /** 记下新算的分，并把整张表写回本地文件（键变了就丢弃，不写）。 */
    @Synchronized
    fun store(key: String, computed: Map<String, Pair<WorkFeature, LightScore>>) {
        if (computed.isEmpty() || memoryKey != key) return
        if (memory.size + computed.size > MAX_ENTRIES) memory.clear()
        computed.forEach { (id, pair) -> memory[id] = Entry.of(pair.first.updatedAt, pair.second) }
        runCatchingCancellable {
            val temp = File(context.filesDir, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString(Stored.serializer(), Stored(key, memory.toMap())))
            temp.renameTo(file)
        }
    }

    private fun readDisk(key: String): Map<String, Entry>? = runCatchingCancellable {
        file.takeIf { it.exists() }?.readText()
            ?.let { json.decodeFromString(Stored.serializer(), it) }
            ?.takeIf { it.key == key }
            ?.entries
    }.getOrNull()

    @Serializable
    private data class Stored(val key: String, val entries: Map<String, Entry>)

    @Serializable
    private data class Entry(
        val updatedAt: Long,
        val score: Int,
        val satisfaction: Int,
        val familiarity: Double,
        val highChance: Double? = null,
    ) {
        fun toScore() = LightScore(score, satisfaction, familiarity, highChance)

        companion object {
            fun of(updatedAt: Long, s: LightScore) =
                Entry(updatedAt, s.score, s.satisfaction, s.familiarity, s.highChance)
        }
    }

    private companion object {
        const val FILE_NAME = "taste-light-scores.json"
        const val MAX_ENTRIES = 30_000
    }
}
