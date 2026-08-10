package com.acgcompass.data.backup

import com.acgcompass.data.datastore.SettingsState
import com.acgcompass.data.datastore.ThemeMode
import com.acgcompass.data.local.entity.BacklogItemEntity
import com.acgcompass.data.local.entity.ChangeLogEntity
import com.acgcompass.data.local.entity.ImportBatchEntity
import com.acgcompass.data.local.entity.ImportItemEntity
import com.acgcompass.data.local.entity.RatingEntity
import com.acgcompass.data.local.entity.RecommendationCountEntity
import com.acgcompass.data.local.entity.SnapshotEntity
import com.acgcompass.data.local.entity.SourceLinkEntity
import com.acgcompass.data.local.entity.TagDimensionEntity
import com.acgcompass.data.local.entity.TagEntity
import com.acgcompass.data.local.entity.TasteProfileEntity
import com.acgcompass.data.local.entity.TasteTagStatEntity
import com.acgcompass.data.local.entity.UserCollectionEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.local.entity.WorkFeatureEntity
import com.acgcompass.data.local.entity.WorkTagEntity

/**
 * Room 实体 <-> 备份模型的**纯**双向映射（无 Android / IO 依赖）。
 *
 * 每个 `toBackup()` / `toEntity()` 对均为字段一一对应的无损映射，保证经由备份 round-trip
 * （导出再导入）后实体内容业务等价。设置以 [ThemeMode] 的稳定字符串
 * 表示持久化，未知值回退为 `SYSTEM`。
 */

// --- Work ------------------------------------------------------------------

fun WorkEntity.toBackup(): BackupWork = BackupWork(
    id = id,
    canonicalTitle = canonicalTitle,
    titleJa = titleJa,
    titleRomaji = titleRomaji,
    titleEn = titleEn,
    aliases = aliases,
    mediaType = mediaType,
    year = year,
    status = status,
    episodes = episodes,
    episodeMinutes = episodeMinutes,
    volumes = volumes,
    estPlayMinutes = estPlayMinutes,
    coverUrl = coverUrl,
    primarySource = primarySource,
    completionCostBucket = completionCostBucket,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

fun BackupWork.toEntity(): WorkEntity = WorkEntity(
    id = id,
    canonicalTitle = canonicalTitle,
    titleJa = titleJa,
    titleRomaji = titleRomaji,
    titleEn = titleEn,
    aliases = aliases,
    mediaType = mediaType,
    year = year,
    status = status,
    episodes = episodes,
    episodeMinutes = episodeMinutes,
    volumes = volumes,
    estPlayMinutes = estPlayMinutes,
    coverUrl = coverUrl,
    primarySource = primarySource,
    completionCostBucket = completionCostBucket,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// --- SourceLink ------------------------------------------------------------

fun SourceLinkEntity.toBackup(): BackupSourceLink = BackupSourceLink(
    id = id,
    workId = workId,
    sourceId = sourceId,
    sourceItemId = sourceItemId,
    matchConfidence = matchConfidence,
    userOverridden = userOverridden,
    linkedAt = linkedAt,
)

fun BackupSourceLink.toEntity(): SourceLinkEntity = SourceLinkEntity(
    id = id,
    workId = workId,
    sourceId = sourceId,
    sourceItemId = sourceItemId,
    matchConfidence = matchConfidence,
    userOverridden = userOverridden,
    linkedAt = linkedAt,
)

// --- RecommendationCount ---------------------------------------------------

fun RecommendationCountEntity.toBackup(): BackupRecommendationCount = BackupRecommendationCount(
    workId = workId,
    recommendedCount = recommendedCount,
    lastRecommendedAt = lastRecommendedAt,
)

fun BackupRecommendationCount.toEntity(): RecommendationCountEntity = RecommendationCountEntity(
    workId = workId,
    recommendedCount = recommendedCount,
    lastRecommendedAt = lastRecommendedAt,
)

// --- BacklogItem -----------------------------------------------------------

fun BacklogItemEntity.toBackup(): BackupBacklogItem = BackupBacklogItem(
    workId = workId,
    priority = priority,
    moodTags = moodTags,
    riskTags = riskTags,
    note = note,
    addedAt = addedAt,
    dustDays = dustDays,
    inDustMuseum = inDustMuseum,
)

fun BackupBacklogItem.toEntity(): BacklogItemEntity = BacklogItemEntity(
    workId = workId,
    priority = priority,
    moodTags = moodTags,
    riskTags = riskTags,
    note = note,
    addedAt = addedAt,
    dustDays = dustDays,
    inDustMuseum = inDustMuseum,
)

// --- Rating ----------------------------------------------------------------

fun RatingEntity.toBackup(): BackupRating = BackupRating(
    id = id,
    workId = workId,
    sourceId = sourceId,
    score = score,
    voteCount = voteCount,
    rank = rank,
    fetchedAt = fetchedAt,
    missing = missing,
)

fun BackupRating.toEntity(): RatingEntity = RatingEntity(
    id = id,
    workId = workId,
    sourceId = sourceId,
    score = score,
    voteCount = voteCount,
    rank = rank,
    fetchedAt = fetchedAt,
    missing = missing,
)

// --- Tag / WorkTag ---------------------------------------------------------

fun TagEntity.toBackup(): BackupTag = BackupTag(id = id, category = category, name = name)

fun BackupTag.toEntity(): TagEntity = TagEntity(id = id, category = category, name = name)

fun WorkTagEntity.toBackup(): BackupWorkTag = BackupWorkTag(workId = workId, tagId = tagId)

fun BackupWorkTag.toEntity(): WorkTagEntity = WorkTagEntity(workId = workId, tagId = tagId)

// --- Import ----------------------------------------------------------------

fun ImportBatchEntity.toBackup(): BackupImportBatch = BackupImportBatch(
    id = id,
    name = name,
    createdAt = createdAt,
    source = source,
    recognizedCount = recognizedCount,
    successCount = successCount,
    failureCount = failureCount,
)

fun BackupImportBatch.toEntity(): ImportBatchEntity = ImportBatchEntity(
    id = id,
    name = name,
    createdAt = createdAt,
    source = source,
    recognizedCount = recognizedCount,
    successCount = successCount,
    failureCount = failureCount,
)

fun ImportItemEntity.toBackup(): BackupImportItem = BackupImportItem(
    id = id,
    batchId = batchId,
    rawText = rawText,
    parsedTitle = parsedTitle,
    workId = workId,
    matchConfidence = matchConfidence,
    status = status,
)

fun BackupImportItem.toEntity(): ImportItemEntity = ImportItemEntity(
    id = id,
    batchId = batchId,
    rawText = rawText,
    parsedTitle = parsedTitle,
    workId = workId,
    matchConfidence = matchConfidence,
    status = status,
)

// --- Snapshot / ChangeLog --------------------------------------------------

fun SnapshotEntity.toBackup(): BackupSnapshot = BackupSnapshot(
    id = id,
    takenAt = takenAt,
    kind = kind,
    payloadHash = payloadHash,
)

fun BackupSnapshot.toEntity(): SnapshotEntity = SnapshotEntity(
    id = id,
    takenAt = takenAt,
    kind = kind,
    payloadHash = payloadHash,
)

fun ChangeLogEntity.toBackup(): BackupChangeLog = BackupChangeLog(
    id = id,
    snapshotId = snapshotId,
    workId = workId,
    changeType = changeType,
    field = field,
    oldValue = oldValue,
    newValue = newValue,
    changedAt = changedAt,
)

fun BackupChangeLog.toEntity(): ChangeLogEntity = ChangeLogEntity(
    id = id,
    snapshotId = snapshotId,
    workId = workId,
    changeType = changeType,
    field = field,
    oldValue = oldValue,
    newValue = newValue,
    changedAt = changedAt,
)

// --- TasteProfile / TasteTagStat -------------------------------------------

fun TasteProfileEntity.toBackup(): BackupTasteProfile = BackupTasteProfile(
    id = id,
    strictness = strictness,
    avgScore = avgScore,
    highScoreRarity = highScoreRarity,
    commonScoreBand = commonScoreBand,
    titles = titles,
    confidence = confidence,
    generatedAt = generatedAt,
)

fun BackupTasteProfile.toEntity(): TasteProfileEntity = TasteProfileEntity(
    id = id,
    strictness = strictness,
    avgScore = avgScore,
    highScoreRarity = highScoreRarity,
    commonScoreBand = commonScoreBand,
    titles = titles,
    confidence = confidence,
    generatedAt = generatedAt,
)

fun TasteTagStatEntity.toBackup(): BackupTasteTagStat = BackupTasteTagStat(
    id = id,
    profileId = profileId,
    tagName = tagName,
    bucket = bucket,
    count = count,
)

fun BackupTasteTagStat.toEntity(): TasteTagStatEntity = TasteTagStatEntity(
    id = id,
    profileId = profileId,
    tagName = tagName,
    bucket = bucket,
    count = count,
)

// --- Settings --------------------------------------------------------------

fun SettingsState.toBackup(): BackupSettings = BackupSettings(
    allowAiAnalyzeReviews = allowAiAnalyzeReviews,
    recordTimeMachineSnapshots = recordTimeMachineSnapshots,
    bangumiEnabled = bangumiEnabled,
    anilistEnabled = anilistEnabled,
    jikanEnabled = jikanEnabled,
    malEnabled = malEnabled,
    vndbEnabled = vndbEnabled,
    showAdultContent = showAdultContent,
    autoTranslateVndbDescription = autoTranslateVndbDescription,
    onboardingShown = onboardingShown,
    themeMode = themeMode.toStorage(),
    dynamicColor = dynamicColor,
    lightThemePreset = lightThemePreset.name,
    darkThemePreset = darkThemePreset.name,
    predictiveBackEnabled = predictiveBackEnabled,
    aiThinkingEnabledProviders = aiThinkingEnabledProviders,
    aiTagClassificationMax = aiTagClassificationMax,
    bangumiApiBaseUrl = bangumiApiBaseUrl,
    bangumiNonOfficialTokenConsent = bangumiNonOfficialTokenConsent,
    autoSyncIntervalMinutes = autoSyncIntervalMinutes,
    recommendMinCommunityScore = recommendMinCommunityScore,
    tasteMatchThreshold = tasteMatchThreshold,
    autoCacheMaxCount = autoCacheMaxCount,
    cacheLimitMode = cacheLimitMode.name,
    cacheSpaceLimitMb = cacheSpaceLimitMb,
    cacheBackgroundFillEnabled = cacheBackgroundFillEnabled,
)

fun BackupSettings.toSettingsState(): SettingsState = SettingsState(
    allowAiAnalyzeReviews = allowAiAnalyzeReviews,
    recordTimeMachineSnapshots = recordTimeMachineSnapshots,
    bangumiEnabled = bangumiEnabled,
    anilistEnabled = anilistEnabled,
    jikanEnabled = jikanEnabled,
    malEnabled = malEnabled,
    vndbEnabled = vndbEnabled,
    showAdultContent = showAdultContent,
    autoTranslateVndbDescription = autoTranslateVndbDescription,
    onboardingShown = onboardingShown,
    themeMode = ThemeMode.fromStorage(themeMode),
    dynamicColor = dynamicColor,
    lightThemePreset = com.acgcompass.domain.model.ThemePreset.fromStorage(lightThemePreset),
    darkThemePreset = com.acgcompass.domain.model.ThemePreset.fromStorage(darkThemePreset),
    predictiveBackEnabled = predictiveBackEnabled,
    aiThinkingEnabledProviders = aiThinkingEnabledProviders,
    aiTagClassificationMax = aiTagClassificationMax,
    bangumiApiBaseUrl = bangumiApiBaseUrl,
    bangumiNonOfficialTokenConsent = bangumiNonOfficialTokenConsent,
    autoSyncIntervalMinutes = autoSyncIntervalMinutes,
    recommendMinCommunityScore = recommendMinCommunityScore,
    tasteMatchThreshold = tasteMatchThreshold,
    autoCacheMaxCount = autoCacheMaxCount,
    cacheLimitMode = com.acgcompass.domain.cache.CacheLimitMode.entries
        .firstOrNull { it.name == cacheLimitMode }
        ?: com.acgcompass.domain.cache.CacheLimitMode.COUNT,
    cacheSpaceLimitMb = cacheSpaceLimitMb,
    cacheBackgroundFillEnabled = cacheBackgroundFillEnabled,
)

fun UserCollectionEntity.toBackup(): BackupUserCollection = BackupUserCollection(
    id, source, sourceItemId, localWorkId, status, rating, progress, comment, tags,
    updatedAt, syncedAt, sourceUpdatedAt, isPrivate,
)

fun BackupUserCollection.toEntity(): UserCollectionEntity = UserCollectionEntity(
    id, source, sourceItemId, localWorkId, status, rating, progress, comment, tags,
    updatedAt, syncedAt, sourceUpdatedAt, isPrivate,
)

fun WorkFeatureEntity.toBackup(): BackupWorkFeature = BackupWorkFeature(
    subjectId, tagCountsJson, staff, characters, cv, bangumiScore, bangumiVotes, eps,
    durationMin, platform, mediaType, titles, baseDataUpdatedAt, extendedDataUpdatedAt, completeness, updatedAt,
)

fun BackupWorkFeature.toEntity(): WorkFeatureEntity = WorkFeatureEntity(
    subjectId, tagCountsJson, staff, characters, cv, bangumiScore, bangumiVotes, eps,
    durationMin, platform, mediaType, titles, baseDataUpdatedAt, extendedDataUpdatedAt, completeness, updatedAt,
)

fun TagDimensionEntity.toBackup(): BackupTagDimension = BackupTagDimension(
    tag = tag,
    dimension = dimension,
    source = source,
    confidence = confidence,
    updatedAt = updatedAt,
    classifierVersion = classifierVersion,
    valid = valid,
)

fun BackupTagDimension.toEntity(): TagDimensionEntity = TagDimensionEntity(
    tag = tag,
    dimension = dimension,
    source = source,
    confidence = confidence,
    updatedAt = updatedAt,
    classifierVersion = classifierVersion,
    valid = valid,
)
