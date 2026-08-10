package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.common.runCatchingApp
import com.acgcompass.data.local.dao.TasteDao
import com.acgcompass.data.local.dao.UserCollectionDao
import com.acgcompass.data.local.entity.TasteProfileEntity
import com.acgcompass.data.local.entity.TasteTagStatEntity
import com.acgcompass.domain.model.TagBucket
import com.acgcompass.domain.model.TasteProfile
import com.acgcompass.domain.model.TasteTagStat
import com.acgcompass.domain.repository.TasteProfileRepository
import com.acgcompass.domain.usecase.NameCount
import com.acgcompass.domain.usecase.ScoringHabitCalculator
import com.acgcompass.domain.usecase.TasteInputRecord
import com.acgcompass.domain.usecase.TasteStats
import com.acgcompass.domain.usecase.TasteStatsCalculator
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [TasteProfileRepository] 实现。
 *
 * **单一可信源 = Room**：观察始终从 [TasteDao] 的 Flow 出发。
 *
 * 职责：
 * - [importAndCompute] 调用纯函数 [TasteStatsCalculator] 统计高分/低分标签、常用短评词与常见
 *   搁置类型，再据样本量计算置信度（样本越少越低），最后落库为
 *   [TasteProfileEntity] 聚合 + 每标签 [TasteTagStatEntity] 行。
 * - [observeTasteProfile] 联结最近一次画像与其标签统计行，映射回领域 [TasteProfile]。
 *
 * 桶映射：高分/低分标签写入 [TagBucket.HIGH_SCORE]/[TagBucket.LOW_SCORE]；常用短评词与搁置类型
 * 以扩展桶字符串（[BUCKET_REVIEW_WORD]/[BUCKET_DROPPED]）持久化，供 （口味黑洞）消费，
 * 不污染领域模型的高/低分 `tagStats`（读回时仅保留可解析为高/低分桶的行）。
 *
 * 评分习惯字段（严格度/平均分/高分稀有度/分数段）与口味称号由领域用例计算，此处置为中性占位。
 */
@Singleton
class TasteProfileRepositoryImpl @Inject constructor(
    private val tasteDao: TasteDao,
    private val userCollectionDao: UserCollectionDao,
    private val calculator: TasteStatsCalculator,
    private val habitCalculator: ScoringHabitCalculator,
    private val dispatchers: DispatcherProvider,
) : TasteProfileRepository {

    // --- observeTasteProfile  ---------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTasteProfile(): Flow<TasteProfile?> = tasteDao.observeLatestProfile().flatMapLatest { profile ->
        if (profile == null) {
            flowOf(null)
        } else {
            tasteDao.observeTagStats(profile.id).map { stats ->
                profile.toDomain(stats)
            }
        }
    }

    // --- importAndCompute  ------------------------------------

    override suspend fun importAndCompute(records: List<TasteInputRecord>): AppResult<TasteProfile> =
        withContext(dispatchers.io) {
            runCatchingApp { computeAndPersist(records) }
        }

    // --- 本地重算 -----------------------------------------------------------

    override suspend fun recomputeFromLocal(): AppResult<TasteProfile?> = withContext(dispatchers.io) {
        runCatchingApp {
            val records = userCollectionDao.getAll().map { c ->
                TasteInputRecord(
                    rating = c.rating,
                    tags = c.tags,
                    reviewText = c.comment,
                    status = c.status,
                    updatedAt = c.updatedAt,
                )
            }
            // 无任何本地收藏 / 评分：不生成画像（不伪造），返回 null。
            if (records.isEmpty()) null else computeAndPersist(records)
        }
    }

    /** 计算统计 + 覆盖式落库（仅留最新一份画像），返回新画像。供 import 与本地重算复用。 */
    private suspend fun computeAndPersist(records: List<TasteInputRecord>): TasteProfile {
        val stats = calculator(records)
        val habit = habitCalculator(records, stats)
        val profileId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val profileEntity = TasteProfileEntity(
            id = profileId,
            // 评分习惯：由用户评分推导，无评分样本时为中性 / 缺失，不伪造。
            strictness = habit.strictness,
            avgScore = habit.avgScore,
            highScoreRarity = habit.highScoreRarity,
            commonScoreBand = habit.commonScoreBand,
            titles = habit.titles,
            confidence = confidenceFor(stats.sampleSize),
            generatedAt = now,
        )
        val tagStatRows = stats.toTagStatRows(profileId)

        // 口味画像只需保留最新一份（observeLatestProfile 取最新）。先快照写入前的旧画像，
        // 写入本次画像后再删除旧画像及其标签行，避免自动重算导致 taste_profiles 持续膨胀。
        val staleProfiles = tasteDao.getAllProfiles()
        // 覆盖式写入：先清掉（本次 id 的）旧标签行再 upsert，保证读回的画像与本次统计一致。
        tasteDao.upsertProfile(profileEntity)
        tasteDao.deleteTagStatsForProfile(profileId)
        tasteDao.upsertTagStats(tagStatRows)
        staleProfiles.asSequence().filter { it.id != profileId }.forEach { old ->
            tasteDao.deleteTagStatsForProfile(old.id)
            tasteDao.deleteProfile(old)
        }

        return profileEntity.toDomain(tagStatRows)
    }

    // --- mapping -----------------------------------------------------------

    private fun TasteStats.toTagStatRows(profileId: String): List<TasteTagStatEntity> {
        val rows = ArrayList<TasteTagStatEntity>()
        rows += highScoreTags.toRows(profileId, TagBucket.HIGH_SCORE.name)
        rows += lowScoreTags.toRows(profileId, TagBucket.LOW_SCORE.name)
        rows += commonReviewWords.toRows(profileId, BUCKET_REVIEW_WORD)
        rows += droppedTypes.toRows(profileId, BUCKET_DROPPED)
        return rows
    }

    private fun List<NameCount>.toRows(profileId: String, bucket: String): List<TasteTagStatEntity> = map { nameCount ->
        TasteTagStatEntity(
            id = "$profileId:$bucket:${nameCount.name}",
            profileId = profileId,
            tagName = nameCount.name,
            bucket = bucket,
            count = nameCount.count,
        )
    }

    private fun TasteProfileEntity.toDomain(stats: List<TasteTagStatEntity>): TasteProfile = TasteProfile(
        id = id,
        strictness = strictness,
        avgScore = avgScore,
        highScoreRarity = highScoreRarity,
        commonScoreBand = commonScoreBand,
        titles = titles,
        confidence = confidence,
        generatedAt = generatedAt,
        // 领域模型的 tagStats 仅建模高/低分桶；扩展桶（短评词/搁置类型）保留在库中供  消费。
        tagStats = stats
            .filter { TagBucket.fromStorage(it.bucket) != TagBucket.UNKNOWN }
            .map {
                TasteTagStat(
                    tagName = it.tagName,
                    bucket = TagBucket.fromStorage(it.bucket),
                    count = it.count,
                )
            },
        // 口味黑洞：从搁置/抛弃类型扩展桶按计数降序取出，绝不伪造。
        blackHole = stats
            .filter { it.bucket == BUCKET_DROPPED }
            .sortedByDescending { it.count }
            .map { it.tagName },
    )

    companion object {
        /** 高置信样本下界：样本数 `>=` 此值时置信度达上限。 */
        const val HIGH_CONFIDENCE_SAMPLE: Int = 30

        /** 置信度上限。 */
        const val MAX_CONFIDENCE: Float = 0.95f

        /** 扩展桶：常用短评词（不属高/低分桶，供  使用）。 */
        const val BUCKET_REVIEW_WORD: String = "REVIEW_WORD"

        /** 扩展桶：常见搁置/抛弃类型（口味黑洞输入）。 */
        const val BUCKET_DROPPED: String = "DROPPED_TYPE"

        /**
         * 据样本量计算画像置信度：样本越少置信越低，随样本线性升高，
         * 在 [HIGH_CONFIDENCE_SAMPLE] 处达 [MAX_CONFIDENCE]。空样本置信为 0。
         */
        fun confidenceFor(sampleSize: Int): Float {
            if (sampleSize <= 0) return 0f
            val ratio = sampleSize.toFloat() / HIGH_CONFIDENCE_SAMPLE
            return (ratio * MAX_CONFIDENCE).coerceIn(0f, MAX_CONFIDENCE)
        }
    }
}
