package com.acgcompass.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.acgcompass.core.common.AppError
import com.acgcompass.core.common.AppResult
import com.acgcompass.core.network.RetrofitFactory
import com.acgcompass.core.network.SourceQuota
import com.acgcompass.core.network.graphql.GraphQlClient
import com.acgcompass.core.network.interceptor.RateLimiterRegistry
import com.acgcompass.data.credential.CredentialStatus
import com.acgcompass.data.credential.CredentialStore
import com.acgcompass.data.credential.RedactedCredentials
import com.acgcompass.data.credential.SecretBundle
import com.acgcompass.data.credential.SourceId as CredentialSourceId
import com.acgcompass.data.datastore.SettingsDataStore
import com.acgcompass.data.local.AcgCompassDatabase
import com.acgcompass.data.local.entity.RatingEntity
import com.acgcompass.data.local.entity.WorkEntity
import com.acgcompass.data.remote.anilist.AniListApi
import com.acgcompass.data.remote.anilist.AniListRemoteDataSource
import com.acgcompass.data.remote.bangumi.BangumiApi
import com.acgcompass.data.remote.bangumi.BangumiNextApi
import com.acgcompass.data.remote.bangumi.BangumiRemoteDataSource
import com.acgcompass.data.remote.mal.MalApi
import com.acgcompass.data.remote.mal.MalRemoteDataSource
import com.acgcompass.data.remote.tenrai.TenraiApi
import com.acgcompass.data.remote.tenrai.TenraiRemoteDataSource
import com.acgcompass.data.remote.vndb.VndbApi
import com.acgcompass.data.remote.vndb.VndbRemoteDataSource
import com.acgcompass.data.sync.CacheFillSourcePager
import com.acgcompass.data.sync.PublicCacheFillCoordinator
import com.acgcompass.data.tasks.TaskRunRecorder
import com.acgcompass.domain.model.BackgroundTaskKind
import com.acgcompass.domain.model.RatingBackfillOutcome
import com.acgcompass.domain.model.SourceId
import com.acgcompass.domain.model.SourceRef
import com.acgcompass.domain.usecase.AggregateRatingsUseCase
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [WorkRepositoryImpl] 对外行为的回归测试：内存数据库 + 模拟各数据源接口。
 * 覆盖搜索落库与源链接、评分缓存是否联网、刷新后的评分与后台任务记录、手动匹配保留、公共缓存失败路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkRepositoryBehaviorTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AcgCompassDatabase
    private lateinit var server: MockWebServer
    private val requests = AtomicInteger(0)

    /** 默认全部 404；用例里按路径给出固定响应。 */
    @Volatile
    private var routes: (RecordedRequest) -> MockResponse? = { null }

    @Volatile
    private var fallbackCode = HTTP_NOT_FOUND

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AcgCompassDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.incrementAndGet()
                return routes(request) ?: MockResponse().setResponseCode(fallbackCode)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    @Test
    fun searchPersistsWorkLinkAndCacheEntry() = runBlocking {
        routes = { req -> if (req.path?.startsWith("/bgm/v0/search/subjects") == true) json(SEARCH_BODY) else null }

        val result = repository().search("测试作品")

        val matches = (result as AppResult.Success).data
        assertEquals(listOf("100"), matches.filter { it.sourceTag == SourceId.BANGUMI }.map { it.work.id })
        assertEquals("测试作品", db.workDao().getById("100")?.canonicalTitle)
        val link = db.sourceLinkDao().getBySourceItem(SourceId.BANGUMI.name, "100")
        assertEquals("100", link?.workId)
        assertEquals(false, link?.userOverridden)
        assertTrue(db.cacheDao().getEntriesOldestFirst().any { it.workId == "100" })
    }

    @Test
    fun manualMatchStaysOverriddenAfterSearch() = runBlocking {
        val repo = repository()
        repo.overrideMatch("LOCAL", SourceRef(SourceId.BANGUMI, "100", matchConfidence = 0.5f, userOverridden = false))
        routes = { req -> if (req.path?.startsWith("/bgm/v0/search/subjects") == true) json(SEARCH_BODY) else null }

        repo.search("测试作品")

        val link = db.sourceLinkDao().getBySourceItem(SourceId.BANGUMI.name, "100")
        assertEquals(true, link?.userOverridden)
        assertEquals("LOCAL:BANGUMI:100", link?.id)
    }

    @Test
    fun freshCachedRatingsDoNotHitNetwork() = runBlocking {
        val now = System.currentTimeMillis()
        db.workDao().upsert(work(now))
        db.ratingDao().upsert(rating(SourceId.BANGUMI, score = 7.5f, votes = 500, fetchedAt = now))
        db.ratingDao().upsert(rating(SourceId.TENRAI, score = 7.8f, votes = 900, fetchedAt = now))

        val aggregate = (repository().aggregateRatings("100", forceRefresh = false) as AppResult.Success).data

        assertEquals(0, requests.get())
        assertEquals(500, aggregate.perSource[SourceId.BANGUMI]?.voteCount)
        assertEquals(900, aggregate.perSource[SourceId.TENRAI]?.voteCount)
    }

    @Test
    fun staleBangumiRatingIsRefreshedAndOutcomeRecorded() = runBlocking {
        db.workDao().upsert(work(System.currentTimeMillis()))
        db.ratingDao().upsert(rating(SourceId.BANGUMI, score = 6.0f, votes = 10, fetchedAt = 0L))
        routes = { req -> if (req.path == "/bgm/v0/subjects/100") json(SUBJECT_BODY) else null }

        val aggregate = (repository().aggregateRatings("100", forceRefresh = false) as AppResult.Success).data

        assertEquals(500, aggregate.perSource[SourceId.BANGUMI]?.voteCount)
        val row = db.ratingDao().getByWork("100").single { it.sourceId == SourceId.BANGUMI.name }
        assertEquals(7.5f, row.score)
        val run = db.taskRunDao().observeAll().first().single { it.taskKey == BackgroundTaskKind.DETAIL_RATINGS.key }
        assertTrue(run.lastSummary.orEmpty().contains("《测试作品》"))
    }

    @Test
    fun cachedAggregateBatchGroupsRowsPerWork() = runBlocking {
        db.ratingDao().upsert(rating(SourceId.BANGUMI, score = 7f, votes = 100, fetchedAt = 1L))
        db.ratingDao().upsert(rating(SourceId.ANILIST, score = 8f, votes = 200, fetchedAt = 1L, workId = "200"))

        val result = repository().aggregateRatingsCached(listOf("100", "200", "300"))

        assertEquals(setOf("100", "200", "300"), result.keys)
        assertEquals(100, result.getValue("100").perSource[SourceId.BANGUMI]?.voteCount)
        assertEquals(200, result.getValue("200").perSource[SourceId.ANILIST]?.voteCount)
        assertEquals(0, requests.get())
    }

    @Test
    fun backfillWithServerErrorsIsTransient() = runBlocking {
        db.workDao().upsert(work(System.currentTimeMillis()))
        fallbackCode = HTTP_SERVER_ERROR

        val outcome = repository().backfillRatingsOutcome("100")

        assertEquals(RatingBackfillOutcome.TRANSIENT_FAILURE, outcome)
    }

    @Test
    fun publicDiscoveryWithoutAnySourceFails() = runBlocking {
        val result = repository().loadPublicDiscovery(background = false)

        assertTrue((result as AppResult.Failure).error is AppError.Network)
    }

    @Test
    fun rankingCacheRoundTripSkipsMissingWorks() = runBlocking {
        val now = System.currentTimeMillis()
        db.workDao().upsert(work(now))
        db.ratingDao().upsert(rating(SourceId.BANGUMI, score = 7.5f, votes = 500, fetchedAt = now))
        val repo = repository()

        repo.saveRankingCache("scope", listOf("100", "gone"))

        val cached = repo.getCachedRanking("scope")
        assertEquals(listOf("100"), cached.map { it.first.id })
        assertEquals(500, cached.single().second?.voteCount)
    }

    private fun repository(): WorkRepositoryImpl {
        val client = OkHttpClient()
        fun retrofit(path: String) = RetrofitFactory.create(server.url(path).toString(), client)
        val settings = SettingsDataStore(context)
        val bangumi = BangumiRemoteDataSource(
            retrofit("/bgm/").create(BangumiApi::class.java),
            retrofit("/bgmnext/").create(BangumiNextApi::class.java),
        )
        val anilist = AniListRemoteDataSource(AniListApi(GraphQlClient(client, server.url("/anilist/").toString())))
        val tenrai = TenraiRemoteDataSource(retrofit("/tenrai/v1/").create(TenraiApi::class.java))
        val mal = MalRemoteDataSource(retrofit("/mal/").create(MalApi::class.java), NoCredentials)
        val vndb = VndbRemoteDataSource(retrofit("/vndb/").create(VndbApi::class.java), settings)
        val dispatchers = TestDispatchers()
        val writer = WorkRowWriter(
            workDao = db.workDao(),
            cacheDao = db.cacheDao(),
            sourceLinkDao = db.sourceLinkDao(),
            ratingDao = db.ratingDao(),
            workTagWriter = WorkTagWriter(db.tagDao(), db.workDao()),
            workFeatureRepository = WorkFeatureRepositoryImpl(db.workFeatureDao(), bangumi, dispatchers),
        )
        val ratingFetcher = SourceRatingFetcher(bangumi, anilist, tenrai, mal, vndb)
        val linker = CrossSourceLinker(
            workDao = db.workDao(),
            sourceLinkDao = db.sourceLinkDao(),
            bangumi = bangumi,
            anilist = anilist,
            tenrai = tenrai,
            mal = mal,
            settingsDataStore = settings,
            rowWriter = writer,
            ratingFetcher = ratingFetcher,
        )
        return WorkRepositoryImpl(
            workDao = db.workDao(),
            cacheDao = db.cacheDao(),
            sourceLinkDao = db.sourceLinkDao(),
            ratingDao = db.ratingDao(),
            tagDao = db.tagDao(),
            rankingCacheDao = db.rankingCacheDao(),
            bangumi = bangumi,
            aggregateRatingsUseCase = AggregateRatingsUseCase(),
            dispatchers = dispatchers,
            rowWriter = writer,
            searchCoordinator = WorkSearchCoordinator(bangumi, anilist, mal, vndb, writer, dispatchers),
            publicCacheFiller = publicCacheFiller(Sources(bangumi, anilist, tenrai, mal, vndb), settings, writer),
            ratingRefresher = ratingRefresher(Sources(bangumi, anilist, tenrai, mal, vndb), settings, writer, linker),
        )
    }

    private class Sources(
        val bangumi: BangumiRemoteDataSource,
        val anilist: AniListRemoteDataSource,
        val tenrai: TenraiRemoteDataSource,
        val mal: MalRemoteDataSource,
        val vndb: VndbRemoteDataSource,
    )

    private fun publicCacheFiller(sources: Sources, settings: SettingsDataStore, writer: WorkRowWriter) =
        PublicCacheFiller(
            cacheDao = db.cacheDao(),
            ratingDao = db.ratingDao(),
            vndbRankingCacheDao = db.vndbRankingCacheDao(),
            bangumi = sources.bangumi,
            settingsDataStore = settings,
            cacheFillCoordinator = PublicCacheFillCoordinator(CoroutineScope(SupervisorJob())),
            cacheFillSourcePager = CacheFillSourcePager(
                db.cacheDao(),
                db.vndbRankingCacheDao(),
                sources.anilist,
                sources.tenrai,
                sources.vndb,
            ),
            rowWriter = writer,
            dispatchers = TestDispatchers(),
        )

    private fun ratingRefresher(
        sources: Sources,
        settings: SettingsDataStore,
        writer: WorkRowWriter,
        linker: CrossSourceLinker,
    ) = WorkRatingRefresher(
        workDao = db.workDao(),
        sourceLinkDao = db.sourceLinkDao(),
        ratingDao = db.ratingDao(),
        bangumi = sources.bangumi,
        anilist = sources.anilist,
        tenrai = sources.tenrai,
        mal = sources.mal,
        vndb = sources.vndb,
        aggregateRatingsUseCase = AggregateRatingsUseCase(),
        settingsDataStore = settings,
        sourceQuota = SourceQuota(RateLimiterRegistry.default()),
        taskRecorder = TaskRunRecorder(db.taskRunDao()),
        rowWriter = writer,
        ratingFetcher = SourceRatingFetcher(
            sources.bangumi,
            sources.anilist,
            sources.tenrai,
            sources.mal,
            sources.vndb,
        ),
        crossSourceLinker = linker,
        dispatchers = TestDispatchers(),
    )

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun work(now: Long) = WorkEntity(
        id = "100", canonicalTitle = "测试作品", titleJa = "テスト", titleRomaji = null, titleEn = null,
        titleCn = "测试作品", aliases = emptyList(), mediaType = "ANIME", year = 2024, status = "FINISHED",
        episodes = 12, episodeMinutes = 24, volumes = null, estPlayMinutes = null,
        coverUrl = "https://lain.bgm.tv/pic/cover/l/00/00/100.jpg",
        primarySource = "BANGUMI", completionCostBucket = null, summary = "简介", airDate = "2024-04-05",
        createdAt = now, updatedAt = now,
    )

    private fun rating(source: SourceId, score: Float, votes: Int, fetchedAt: Long, workId: String = "100") =
        RatingEntity(
            id = "$workId:${source.name}",
            workId = workId,
            sourceId = source.name,
            score = score,
            voteCount = votes,
            rank = null,
            fetchedAt = fetchedAt,
            missing = false,
        )

    private object NoCredentials : CredentialStore {
        override suspend fun put(source: CredentialSourceId, secret: SecretBundle) = Unit
        override suspend fun get(source: CredentialSourceId): SecretBundle? = null
        override suspend fun clear(source: CredentialSourceId) = Unit
        override suspend fun clearAll() = Unit
        override fun observeStatus(): Flow<Map<CredentialSourceId, CredentialStatus>> = flowOf(emptyMap())
        override suspend fun exportRedacted(): RedactedCredentials = RedactedCredentials()
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val HTTP_SERVER_ERROR = 500
        const val SUBJECT_BODY = """{"id":100,"type":2,"name":"テスト","name_cn":"测试作品","summary":"简介",""" +
            """"date":"2024-04-05","platform":"TV","eps":12,"rating":{"total":500,"score":7.5}}"""
        const val SEARCH_BODY = """{"total":1,"limit":20,"offset":0,"data":[$SUBJECT_BODY]}"""
    }
}
