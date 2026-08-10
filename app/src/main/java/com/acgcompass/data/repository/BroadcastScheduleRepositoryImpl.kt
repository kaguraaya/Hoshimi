package com.acgcompass.data.repository

import com.acgcompass.core.common.AppResult
import com.acgcompass.core.common.DispatcherProvider
import com.acgcompass.core.network.RequestPriority
import com.acgcompass.data.local.dao.BroadcastScheduleDao
import com.acgcompass.data.local.dao.SourceLinkDao
import com.acgcompass.data.local.dao.WorkDao
import com.acgcompass.data.local.entity.BroadcastScheduleEntity
import com.acgcompass.data.local.entity.SourceLinkEntity
import com.acgcompass.data.local.mapper.toEntity
import com.acgcompass.data.remote.anilist.AniListRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiCalendarDayDto
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.jikan.JikanAnimeDto
import com.acgcompass.data.remote.tenrai.TenraiRemoteDataSource
import com.acgcompass.domain.model.MediaType
import com.acgcompass.domain.model.ReleaseStatus
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.Titles
import com.acgcompass.domain.model.Work
import com.acgcompass.domain.schedule.BroadcastScheduleEntry
import com.acgcompass.domain.schedule.BroadcastScheduleRepository
import com.acgcompass.domain.schedule.WeeklyBroadcastSchedule
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
@Suppress("TooManyFunctions", "ReturnCount")
class BroadcastScheduleRepositoryImpl @Inject constructor(
    private val scheduleDao: BroadcastScheduleDao,
    private val workDao: WorkDao,
    private val sourceLinkDao: SourceLinkDao,
    private val aniList: AniListRemoteDataSource,
    private val bangumi: BangumiRemoteDataSource,
    private val tenrai: TenraiRemoteDataSource,
    private val dispatchers: DispatcherProvider,
) : BroadcastScheduleRepository {

    private val tenraiScheduleMutex = Mutex()
    private var tenraiScheduleCachedAt = 0L
    private var tenraiScheduleCache: List<JikanAnimeDto> = emptyList()

    override fun observeUpcoming(limit: Int): Flow<List<BroadcastScheduleEntry>> =
        scheduleDao.observeUpcoming(SCOPE_HOME, System.currentTimeMillis(), limit)
            .map { rows -> rows.map { it.toDomain() } }

    override fun observeWeek(): Flow<WeeklyBroadcastSchedule> = scheduleDao
        .observeScope(SCOPE_WEEK)
        .map { rows ->
            val domains = rows.map { it.toDomain() }
            WeeklyBroadcastSchedule(
                entriesByDay = domains.filter { it.workId.toIntOrNull() != null }
                    .groupBy { it.dayOfWeek },
                otherSourceEntries = domains.filter { it.workId.toIntOrNull() == null },
            )
        }

    override suspend fun refreshUpcoming(force: Boolean): Result<Unit> = withContext(dispatchers.io) {
        refreshScope(
            scope = SCOPE_HOME,
            fromMillis = System.currentTimeMillis(),
            toMillis = System.currentTimeMillis() + SEVEN_DAYS_MILLIS,
            ttlMillis = HOME_TTL_MILLIS,
            force = force,
        )
    }

    override suspend fun refreshWeek(force: Boolean): Result<Unit> = withContext(dispatchers.io) {
        val zone = ZoneId.systemDefault()
        val monday = Instant.now().atZone(zone)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .toLocalDate()
            .atStartOfDay(zone)
        val from = monday.toInstant().toEpochMilli()
        refreshScope(
            scope = SCOPE_WEEK,
            fromMillis = from,
            toMillis = from + SEVEN_DAYS_MILLIS,
            ttlMillis = WEEK_TTL_MILLIS,
            force = force,
        )
    }

    private suspend fun refreshScope(
        scope: String,
        fromMillis: Long,
        toMillis: Long,
        ttlMillis: Long,
        force: Boolean,
    ): Result<Unit> {
        val now = System.currentTimeMillis()
        val cachedAt = scheduleDao.latestCacheTime(scope)
        if (!force && cachedAt != null && now - cachedAt < ttlMillis) return Result.success(Unit)

        val bangumiEntries = fallbackFromBangumi(fromMillis, toMillis).getOrNull().orEmpty()
        val remoteEntries = aniList.getAiringSchedules(
            fromEpochSeconds = fromMillis / 1_000L,
            toEpochSeconds = toMillis / 1_000L,
            priority = RequestPriority.BACKGROUND,
        ).getOrNull()
        val needsTenraiTime = needsTenraiScheduleSupplement(bangumiEntries, remoteEntries.orEmpty())
        val tenraiEntries = if (needsTenraiTime) {
            loadTenraiSchedule().mapNotNull { it.toScheduleEntry(fromMillis, toMillis) }
        } else {
            emptyList()
        }
        // Bangumi defines the visible calendar. AniList only enriches a high-confidence match.
        // Bangumi owns the visible calendar. AniList can enrich matched rows, but must not
        // replace the calendar when Bangumi is temporarily unavailable.
        val primaryEntries = bangumiEntries
        if (primaryEntries.isEmpty()) {
            if (scope == SCOPE_WEEK && !remoteEntries.isNullOrEmpty() && scheduleDao.getScope(scope).isEmpty()) {
                scheduleDao.upsertAll(remoteEntries.map { it.toEntity(scope, now) })
            }
            return if (scheduleDao.getScope(scope).isNotEmpty()) {
                Result.failure(IllegalStateException("schedule refresh unavailable"))
            } else {
                Result.failure(IllegalStateException("schedule data unavailable"))
            }
        }

        val scoped = mergeScheduleEntries(
            scope,
            now,
            primaryEntries,
            bangumiEntries,
            remoteEntries,
            tenraiEntries,
        )
        persistWorks(scoped, now)
        val existing = scheduleDao.getScope(scope)
        val oldHashes = existing.associateBy { it.scheduleId }.mapValues { it.value.contentHash }
        val newHashes = scoped.associateBy { it.scheduleId }.mapValues { it.value.contentHash }
        if (oldHashes == newHashes && existing.size == scoped.size) {
            scheduleDao.touchScope(scope, now)
        } else {
            scheduleDao.deleteScope(scope)
            scheduleDao.upsertAll(scoped)
        }
        return Result.success(Unit)
    }

    private fun mergeScheduleEntries(
        scope: String,
        now: Long,
        primaryEntries: List<BroadcastScheduleEntry>,
        bangumiEntries: List<BroadcastScheduleEntry>,
        remoteEntries: List<BroadcastScheduleEntry>?,
        tenraiEntries: List<BroadcastScheduleEntry>,
    ): List<BroadcastScheduleEntity> {
        val scoped = primaryEntries.map { it.toEntity(scope, now) }.toMutableList()
        val matchedAniListIds = mutableSetOf<String>()
        if (bangumiEntries.isNotEmpty() && !remoteEntries.isNullOrEmpty()) {
            remoteEntries.forEach { candidate ->
                val match = bangumiEntries.firstOrNull { bgm ->
                    broadcastScheduleTitlesMatch(bgm, candidate)
                } ?: return@forEach
                matchedAniListIds += candidate.scheduleId
                val index = scoped.indexOfFirst { it.sourceItemId == match.sourceItemId }
                if (index >= 0) {
                    val current = scoped[index]
                    scoped[index] = current.copy(
                        airingAtUtcMillis = candidate.airingAtUtcMillis,
                        episode = candidate.episode,
                        timeToBeDetermined = false,
                        contentHash = hashOf(
                            listOf(
                                current.scheduleId,
                                current.workId,
                                current.title,
                                current.titleCn,
                                current.coverUrl,
                                candidate.airingAtUtcMillis,
                                candidate.episode,
                                false,
                            ),
                        ),
                    )
                }
            }
            if (scope == SCOPE_WEEK) {
                scoped += remoteEntries
                    .asSequence()
                    .filterNot { it.scheduleId in matchedAniListIds }
                    .map { it.toEntity(scope, now) }
                    .toList()
            }
        }
        if (tenraiEntries.isNotEmpty()) {
            scoped.indices.forEach { index ->
                val current = scoped[index]
                if (!current.timeToBeDetermined) return@forEach
                val bangumiEntry = bangumiEntries.firstOrNull { it.sourceItemId == current.sourceItemId }
                    ?: return@forEach
                val supplement = tenraiEntries.firstOrNull { candidate ->
                    broadcastScheduleTitlesMatch(bangumiEntry, candidate)
                } ?: return@forEach
                scoped[index] = current.copy(
                    airingAtUtcMillis = supplement.airingAtUtcMillis,
                    timeToBeDetermined = false,
                    contentHash = hashOf(
                        listOf(
                            current.scheduleId,
                            current.workId,
                            current.title,
                            current.titleCn,
                            current.coverUrl,
                            supplement.airingAtUtcMillis,
                            current.episode,
                            false,
                        ),
                    ),
                )
            }
        }
        return scoped
    }

    private suspend fun loadTenraiSchedule(): List<JikanAnimeDto> = tenraiScheduleMutex.withLock {
        val now = System.currentTimeMillis()
        if (tenraiScheduleCache.isNotEmpty() && now - tenraiScheduleCachedAt < TENRAI_SCHEDULE_TTL_MILLIS) {
            return@withLock tenraiScheduleCache
        }
        val fresh = (tenrai.getSeasonNow(priority = RequestPriority.BACKGROUND) as? AppResult.Success)
            ?.data
            .orEmpty()
        if (fresh.isNotEmpty()) {
            tenraiScheduleCache = fresh
            tenraiScheduleCachedAt = now
        }
        tenraiScheduleCache
    }

    private fun JikanAnimeDto.toScheduleEntry(fromMillis: Long, toMillis: Long): BroadcastScheduleEntry? {
        val broadcast = broadcast ?: return null
        val weekday = englishWeekday(broadcast.day) ?: return null
        val localTime = broadcast.time?.let { value -> runCatching { LocalTime.parse(value) }.getOrNull() }
            ?: return null
        val zone = runCatching { ZoneId.of(broadcast.timezone ?: "Asia/Tokyo") }
            .getOrDefault(ZoneId.of("Asia/Tokyo"))
        val airingAt = nextOccurrenceMillis(weekday, localTime, zone, fromMillis)
            .takeIf { it in fromMillis..toMillis } ?: return null
        return BroadcastScheduleEntry(
            scheduleId = "TENRAI_SCHEDULE:$malId",
            workId = "TENRAI:$malId",
            sourceItemId = malId.toString(),
            title = title.ifBlank { titleEnglish ?: titleJapanese ?: malId.toString() },
            titleCn = null,
            titleVariants = (listOf(title, titleEnglish, titleJapanese) + titleSynonyms)
                .filterNotNull()
                .filter(String::isNotBlank)
                .distinct(),
            coverUrl = null,
            airingAtUtcMillis = airingAt,
            dayOfWeek = weekday,
            episode = null,
            timeToBeDetermined = false,
        )
    }

    private fun nextOccurrenceMillis(weekday: DayOfWeek, time: LocalTime, zone: ZoneId, fromMillis: Long): Long {
        val from = Instant.ofEpochMilli(fromMillis).atZone(zone)
        var date = from.toLocalDate().plusDays(
            ((weekday.value - from.dayOfWeek.value + MAX_DAY_OF_WEEK) % MAX_DAY_OF_WEEK).toLong(),
        )
        var candidate = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (candidate < fromMillis) {
            date = date.plusWeeks(1)
            candidate = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        }
        return candidate
    }

    private fun englishWeekday(raw: String?): DayOfWeek? = when (raw?.take(DAY_PREFIX_LENGTH)?.lowercase()) {
        "mon" -> DayOfWeek.MONDAY
        "tue" -> DayOfWeek.TUESDAY
        "wed" -> DayOfWeek.WEDNESDAY
        "thu" -> DayOfWeek.THURSDAY
        "fri" -> DayOfWeek.FRIDAY
        "sat" -> DayOfWeek.SATURDAY
        "sun" -> DayOfWeek.SUNDAY
        else -> null
    }

    private suspend fun fallbackFromBangumi(fromMillis: Long, toMillis: Long): Result<List<BroadcastScheduleEntry>> {
        val result = bangumi.getCalendarDays(RequestPriority.BACKGROUND)
        val days = (result as? AppResult.Success)?.data
            ?: return Result.failure(IllegalStateException("Bangumi calendar unavailable"))
        val zone = ZoneId.systemDefault()
        return Result.success(
            days.flatMap { day ->
                val weekday = day.weekdayToDayOfWeek() ?: return@flatMap emptyList()
                day.items.filter { it.id > 0 && it.type == 2 }.map { item ->
                    val time = nextOccurrenceMillis(
                        weekday = weekday,
                        fromMillis = fromMillis,
                        zone = zone,
                    )
                    BroadcastScheduleEntry(
                        scheduleId = "BANGUMI_CALENDAR:${item.id}",
                        workId = item.id.toString(),
                        sourceItemId = item.id.toString(),
                        title = item.nameCn.ifBlank { item.name }.ifBlank { item.id.toString() },
                        titleCn = item.nameCn.takeIf { it.isNotBlank() },
                        titleVariants = listOf(item.name, item.nameCn).filter(String::isNotBlank).distinct(),
                        coverUrl = secureImageUrl(item.images?.medium ?: item.images?.common ?: item.images?.large),
                        airingAtUtcMillis = time.takeIf { it in fromMillis..toMillis },
                        dayOfWeek = weekday,
                        episode = null,
                        timeToBeDetermined = true,
                    )
                }
            },
        )
    }

    private fun nextOccurrenceMillis(weekday: DayOfWeek, fromMillis: Long, zone: ZoneId): Long {
        val from = Instant.ofEpochMilli(fromMillis).atZone(zone)
        var date = from.toLocalDate().plusDays(
            ((weekday.value - from.dayOfWeek.value + MAX_DAY_OF_WEEK) % MAX_DAY_OF_WEEK).toLong(),
        )
        // Keep an undetermined-time item visible for the whole local day. If the query starts
        // after that day, move it to the next weekly occurrence.
        var candidate = date.atTime(END_OF_DAY_HOUR, END_OF_DAY_MINUTE, END_OF_DAY_SECOND)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
        if (candidate < fromMillis) {
            date = date.plusWeeks(1)
            candidate = date.atTime(END_OF_DAY_HOUR, END_OF_DAY_MINUTE, END_OF_DAY_SECOND)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
        }
        return candidate
    }

    private suspend fun persistWorks(entries: List<BroadcastScheduleEntity>, now: Long) {
        entries.forEach { entry ->
            val old = workDao.getById(entry.workId)
            val work = Work(
                id = entry.workId,
                titles = Titles(canonical = entry.title, cn = entry.titleCn),
                mediaType = MediaType.ANIME,
                status = ReleaseStatus.RELEASING,
                coverUrl = entry.coverUrl,
                primarySource = if (entry.source == SourceId.BANGUMI.name) SourceId.BANGUMI else SourceId.ANILIST,
            )
            workDao.upsert(
                work.toEntity(createdAt = old?.createdAt ?: now, updatedAt = now).let { entity ->
                    if (old == null) {
                        entity
                    } else {
                        entity.copy(
                            titleCn = old.titleCn ?: entity.titleCn,
                            summary = old.summary ?: entity.summary,
                        )
                    }
                },
            )
            sourceLinkDao.upsert(
                SourceLinkEntity(
                    id = "${entry.source}:${entry.sourceItemId}",
                    workId = entry.workId,
                    sourceId = entry.source,
                    sourceItemId = entry.sourceItemId,
                    matchConfidence = if (entry.source == SourceId.BANGUMI.name) 1f else 0.5f,
                    userOverridden = false,
                    linkedAt = now,
                ),
            )
        }
    }

    private fun BroadcastScheduleEntity.toDomain() = BroadcastScheduleEntry(
        scheduleId = scheduleId,
        workId = workId,
        sourceItemId = sourceItemId,
        title = title,
        titleCn = titleCn,
        titleVariants = titleVariants,
        coverUrl = secureImageUrl(coverUrl),
        airingAtUtcMillis = airingAtUtcMillis,
        dayOfWeek = DayOfWeek.of(dayOfWeek.coerceIn(MIN_DAY_OF_WEEK, MAX_DAY_OF_WEEK)),
        episode = episode,
        timeToBeDetermined = timeToBeDetermined,
    )

    private fun BroadcastScheduleEntry.toEntity(scope: String, now: Long) = BroadcastScheduleEntity(
        scopeKey = scope,
        scheduleId = scheduleId,
        source = if (workId.startsWith("ANILIST:")) SourceId.ANILIST.name else SourceId.BANGUMI.name,
        sourceItemId = sourceItemId,
        workId = workId,
        title = title,
        titleCn = titleCn,
        titleVariants = titleVariants,
        coverUrl = coverUrl,
        airingAtUtcMillis = airingAtUtcMillis,
        dayOfWeek = dayOfWeek.value,
        episode = episode,
        timeToBeDetermined = timeToBeDetermined,
        contentHash = hashOf(
            listOf(
                scheduleId,
                workId,
                title,
                titleCn,
                titleVariants,
                coverUrl,
                airingAtUtcMillis,
                episode,
                timeToBeDetermined,
            ),
        ),
        cachedAt = now,
    )

    private fun BangumiCalendarDayDto.weekdayToDayOfWeek(): DayOfWeek? = weekday?.id?.let {
        it.takeIf { value -> value in MIN_DAY_OF_WEEK..MAX_DAY_OF_WEEK }?.let(DayOfWeek::of)
    } ?: weekday?.en?.let { text ->
        when (text.take(DAY_PREFIX_LENGTH).lowercase()) {
            "mon" -> DayOfWeek.MONDAY
            "tue" -> DayOfWeek.TUESDAY
            "wed" -> DayOfWeek.WEDNESDAY
            "thu" -> DayOfWeek.THURSDAY
            "fri" -> DayOfWeek.FRIDAY
            "sat" -> DayOfWeek.SATURDAY
            "sun" -> DayOfWeek.SUNDAY
            else -> null
        }
    }

    private fun hashOf(values: List<Any?>): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u001f").toByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun secureImageUrl(url: String?): String? = url?.replaceFirst("http://", "https://")

    private companion object {
        const val SCOPE_HOME = "HOME_UPCOMING"
        const val SCOPE_WEEK = "CURRENT_WEEK"
        const val HOME_TTL_MILLIS = 30L * 60L * 1_000L
        const val WEEK_TTL_MILLIS = 6L * 60L * 60L * 1_000L
        const val TENRAI_SCHEDULE_TTL_MILLIS = 6L * 60L * 60L * 1_000L
        const val SEVEN_DAYS_MILLIS = 7L * 24L * 60L * 60L * 1_000L
        const val MIN_DAY_OF_WEEK = 1
        const val MAX_DAY_OF_WEEK = 7
        const val DAY_PREFIX_LENGTH = 3
        const val END_OF_DAY_HOUR = 23
        const val END_OF_DAY_MINUTE = 59
        const val END_OF_DAY_SECOND = 59
    }
}

internal fun broadcastScheduleTitlesMatch(a: BroadcastScheduleEntry, b: BroadcastScheduleEntry): Boolean {
    fun compactTitle(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
    val left = (a.titleVariants + a.title + listOfNotNull(a.titleCn))
        .map(::compactTitle)
        .filter { it.length >= MIN_NORMALIZED_TITLE_LENGTH }
    val right = (b.titleVariants + b.title + listOfNotNull(b.titleCn))
        .map(::compactTitle)
        .filter { it.length >= MIN_NORMALIZED_TITLE_LENGTH }
    return left.any { it in right }
}

internal fun needsTenraiScheduleSupplement(
    bangumiEntries: List<BroadcastScheduleEntry>,
    aniListEntries: List<BroadcastScheduleEntry>,
): Boolean = bangumiEntries.any { bangumiEntry ->
    bangumiEntry.timeToBeDetermined && aniListEntries.none { aniListEntry ->
        broadcastScheduleTitlesMatch(bangumiEntry, aniListEntry)
    }
}

private const val MIN_NORMALIZED_TITLE_LENGTH = 3
