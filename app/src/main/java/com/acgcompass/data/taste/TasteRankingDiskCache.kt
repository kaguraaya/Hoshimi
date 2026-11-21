package com.acgcompass.data.taste

import android.content.Context
import com.acgcompass.core.common.runCatchingCancellable
import com.acgcompass.domain.taste.RankOwnership
import com.acgcompass.domain.taste.TasteRankItem
import com.acgcompass.domain.taste.TasteRanking
import com.acgcompass.domain.taste.WorkFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 把上次算好的口味排行存成一个 JSON 文件，冷启动后打开「为你排行」也能先显示上次的结果。 */
@Singleton
class TasteRankingDiskCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }

    /** 读上次的结果；当时用的模型版本和 [modelVersion] 不同时不返回（数字已经不是同一个算法算的）。 */
    fun read(modelVersion: String): TasteRanking? = runCatchingCancellable {
        file.takeIf { it.exists() }?.readText()
            ?.let { json.decodeFromString(StoredRanking.serializer(), it) }
            ?.takeIf { it.modelVersion == modelVersion }
            ?.toDomain()
    }.getOrNull()

    /** 先写临时文件再改名，写到一半被打断也不会留下坏文件。 */
    fun write(ranking: TasteRanking, modelVersion: String) {
        runCatchingCancellable {
            val temp = File(context.filesDir, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString(StoredRanking.serializer(), StoredRanking.of(ranking, modelVersion)))
            temp.renameTo(file)
        }
    }

    private companion object {
        const val FILE_NAME = "taste-ranking.json"
    }
}

@Serializable
private data class StoredRanking(
    val items: List<StoredItem>,
    val poolSize: Int,
    val computedAt: Long,
    val unratedCount: Int,
    val modelVersion: String = "",
) {
    fun toDomain() = TasteRanking(items.map { it.toDomain() }, poolSize, computedAt, unratedCount)

    companion object {
        fun of(r: TasteRanking, modelVersion: String) =
            StoredRanking(r.items.map(StoredItem::of), r.poolSize, r.computedAt, r.unratedCount, modelVersion)
    }
}

@Serializable
private data class StoredItem(
    val subjectId: String,
    val title: String,
    val coverUrl: String? = null,
    val year: Int? = null,
    val format: String,
    val finished: Boolean? = null,
    val score: Int,
    val predictedRating: Double? = null,
    val reason: String? = null,
    val ownership: String? = null,
    val myRating: Int? = null,
) {
    fun toDomain() = TasteRankItem(
        subjectId = subjectId,
        title = title,
        coverUrl = coverUrl,
        year = year,
        format = WorkFormat.entries.firstOrNull { it.name == format } ?: WorkFormat.OTHER,
        finished = finished,
        score = score,
        predictedRating = predictedRating,
        reason = reason,
        ownership = RankOwnership.entries.firstOrNull { it.name == ownership },
        myRating = myRating,
    )

    companion object {
        fun of(i: TasteRankItem) = StoredItem(
            i.subjectId, i.title, i.coverUrl, i.year, i.format.name, i.finished, i.score,
            i.predictedRating, i.reason, i.ownership?.name, i.myRating,
        )
    }
}
