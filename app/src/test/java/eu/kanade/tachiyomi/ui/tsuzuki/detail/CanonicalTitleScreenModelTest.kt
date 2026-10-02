package eu.kanade.tachiyomi.ui.tsuzuki.detail

import eu.kanade.tachiyomi.data.tsuzuki.diagnostics.RecordingChapterInventoryDiagnostics
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.artwork.ResolveCanonicalArtwork
import tachiyomi.domain.tsuzuki.artwork.model.ResolvedCanonicalArtwork
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ChapterMutationGate
import tachiyomi.domain.tsuzuki.chapter.interactor.MaterializeInferredChapter
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.inferredChapterId
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.download.interactor.DownloadCanonicalChapter
import tachiyomi.domain.tsuzuki.download.interactor.GetCanonicalChapterDownloadState
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadArtifact
import tachiyomi.domain.tsuzuki.download.model.CanonicalDownloadPreparation
import tachiyomi.domain.tsuzuki.download.repository.CanonicalDownloadRepository
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.integration.interactor.ResolveCanonicalMetadata
import tachiyomi.domain.tsuzuki.integration.model.ProvenancedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedMetadata
import tachiyomi.domain.tsuzuki.integration.model.ResolvedRating
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount
import tachiyomi.domain.tsuzuki.metadata.interactor.RefreshReportedChapterCounts
import tachiyomi.domain.tsuzuki.metadata.repository.ReportedChapterCountRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistory
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterHistoryUpdate
import tachiyomi.domain.tsuzuki.reader.model.CanonicalChapterProgress
import tachiyomi.domain.tsuzuki.reader.repository.CanonicalReadingRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ResolveCanonicalSourceManga

@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalTitleScreenModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `post-binding local reload exposes reconciled chapters without probing providers again`() = runTest(
        dispatcher,
    ) {
        val chapters = FakeChapterRepository(emptyList())
        val evidence = FakeEvidenceRepository()
        val downloads = mockk<CanonicalDownloadRepository>()
        coEvery { downloads.getChapterIdsByCanonicalTitle("title") } returns emptySet()
        val refresh = mockk<RefreshChapterEvidence>()
        coEvery { refresh.executeProgressively("title", false, any()) } returns Result.success(Unit)
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
            materializeInferredChapter = mockk(relaxed = true),
            chapterEvidenceRepository = evidence,
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = chapters,
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                },
            ),
            downloadCanonicalChapter = mockk(relaxed = true),
            canonicalDownloadRepository = downloads,
            reportedChapterCountRepository = FakeReportedChapterCountRepository(),
            addonRepository = FakeAddonRepository(),
            refreshReportedChapterCounts = metadataRefresh(),
            refreshChapterEvidence = refresh,
            resolveCanonicalMetadata = emptyMetadataResolver(),
            resolveCanonicalSourceManga = mockk(relaxed = true),
            resolveCanonicalArtwork = mockk(relaxed = true),
        )
        model.start("title")
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>().chapters shouldBe emptyList()

        chapters.upsert(
            CanonicalChapter(
                id = "chapter-after-binding",
                canonicalTitleId = "title",
                displayNumber = "1",
                baseNumber = 1,
                confidence = 1.0,
                createdAt = 1L,
                updatedAt = 1L,
                confirmation = CanonicalChapterConfirmation.CONFIRMED,
            ),
        )
        model.reloadReconciledChapters()
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
            .chapters.single().chapter.id shouldBe "chapter-after-binding"
        coVerify(exactly = 1) { refresh.executeProgressively("title", false, any()) }
    }

    @Test
    fun `explicit Detail refresh forces chapter revalidation while normal open does not`() = runTest(dispatcher) {
        val chapters = FakeChapterRepository(emptyList())
        val refresh = mockk<RefreshChapterEvidence>()
        coEvery { refresh.executeProgressively("title", any(), any()) } returns Result.success(Unit)
        val downloads = mockk<CanonicalDownloadRepository>()
        coEvery { downloads.getChapterIdsByCanonicalTitle("title") } returns emptySet()
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
            materializeInferredChapter = mockk(relaxed = true),
            chapterEvidenceRepository = FakeEvidenceRepository(),
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = chapters,
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                },
            ),
            downloadCanonicalChapter = mockk(relaxed = true),
            canonicalDownloadRepository = downloads,
            reportedChapterCountRepository = FakeReportedChapterCountRepository(),
            addonRepository = FakeAddonRepository(),
            refreshReportedChapterCounts = metadataRefresh(),
            refreshChapterEvidence = refresh,
            resolveCanonicalMetadata = emptyMetadataResolver(),
            resolveCanonicalSourceManga = mockk(relaxed = true),
            resolveCanonicalArtwork = mockk(relaxed = true),
        )

        model.start("title")
        advanceUntilIdle()
        coVerify(exactly = 1) { refresh.executeProgressively("title", false, any()) }
        coVerify(exactly = 0) { refresh.executeProgressively("title", true, any()) }

        model.refresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { refresh.executeProgressively("title", true, any()) }
    }

    @Test
    fun `detail publishes cached provider metadata before live revalidation completes`() = runTest(dispatcher) {
        val refreshGate = CompletableDeferred<Unit>()
        val cachedMetadata = ResolvedMetadata(
            synopsis = ProvenancedMetadata(
                value = "Cached synopsis",
                providerId = IntegrationId("kitsu"),
                attribution = "Kitsu",
            ),
        )
        val resolver = mockk<ResolveCanonicalMetadata>()
        coEvery { resolver.cached("title") } returns cachedMetadata
        coEvery { resolver.execute("title", false) } coAnswers {
            refreshGate.await()
            Result.success(cachedMetadata)
        }
        val chapters = FakeChapterRepository(emptyList())
        val downloads = mockk<CanonicalDownloadRepository>()
        coEvery { downloads.getChapterIdsByCanonicalTitle("title") } returns emptySet()
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
            materializeInferredChapter = mockk(relaxed = true),
            chapterEvidenceRepository = FakeEvidenceRepository(),
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = chapters,
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                },
            ),
            downloadCanonicalChapter = mockk(relaxed = true),
            canonicalDownloadRepository = downloads,
            reportedChapterCountRepository = FakeReportedChapterCountRepository(),
            addonRepository = FakeAddonRepository(),
            refreshReportedChapterCounts = metadataRefresh(),
            refreshChapterEvidence = RefreshChapterEvidence(
                registry = emptyRegistry(),
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = chapters,
                    evidenceRepository = FakeEvidenceRepository(),
                ),
            ),
            resolveCanonicalMetadata = resolver,
            resolveCanonicalSourceManga = mockk(relaxed = true),
            resolveCanonicalArtwork = mockk(relaxed = true),
        )

        val operation = model.start("title")
        runCurrent()

        val cachedState = model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
        cachedState.description shouldBe "Cached synopsis"
        cachedState.isRefreshing shouldBe true
        operation.isCompleted shouldBe false

        refreshGate.complete(Unit)
        advanceUntilIdle()
        operation.isCompleted shouldBe true
    }

    // Physical regression guard: production DI must deliver canonical artwork to Detail.
    @Test
    fun `detail exposes enriched provider metadata after background refresh`() = runTest(dispatcher) {
        val sourceResolver = mockk<ResolveCanonicalSourceManga>()
        coEvery {
            sourceResolver.execute(canonicalTitleId = "title", allowNetwork = false)
        } returns Manga.create().copy(
            id = 77L,
            source = 10L,
            url = "/dandadan",
            title = "Dandadan",
            thumbnailUrl = "https://cdn.example/dandadan.jpg",
        )
        val artworkResolver = mockk<ResolveCanonicalArtwork>()
        coEvery { artworkResolver.execute("title") } returns ResolvedCanonicalArtwork(
            coverUrl = "https://kitsu.example/dandadan.jpg",
            coverProvider = "kitsu",
            bannerUrl = null,
            bannerProvider = null,
        )
        val resolver = mockk<ResolveCanonicalMetadata>()
        coEvery { resolver.execute("title") } returns Result.success(
            ResolvedMetadata(
                tags = ProvenancedMetadata(
                    value = listOf("Psychological", "Crime"),
                    providerId = IntegrationId("mangaupdates"),
                    attribution = "MangaUpdates",
                ),
                status = ProvenancedMetadata(
                    value = "COMPLETED",
                    providerId = IntegrationId("mangaupdates"),
                    attribution = "MangaUpdates",
                ),
                format = ProvenancedMetadata(
                    value = "MANGA",
                    providerId = IntegrationId("mangaupdates"),
                    attribution = "MangaUpdates",
                ),
                ratingDetails = ProvenancedMetadata(
                    value = ResolvedRating(
                        value = 8.72,
                        maxValue = 10.0,
                        voteCount = 143215,
                    ),
                    providerId = IntegrationId("mal"),
                    attribution = "MyAnimeList",
                ),
                ratings = listOf(
                    ProvenancedMetadata(
                        value = ResolvedRating(
                            value = 8.72,
                            maxValue = 10.0,
                            voteCount = 143215,
                        ),
                        providerId = IntegrationId("mal"),
                        attribution = "MyAnimeList",
                    ),
                    ProvenancedMetadata(
                        value = ResolvedRating(
                            value = 9.12,
                            maxValue = 10.0,
                            voteCount = 12345,
                        ),
                        providerId = IntegrationId("mangaupdates"),
                        attribution = "MangaUpdates",
                    ),
                ),
                startDate = ProvenancedMetadata(
                    value = "1994",
                    providerId = IntegrationId("mangaupdates"),
                    attribution = "MangaUpdates",
                ),
                endDate = ProvenancedMetadata(
                    value = "2001",
                    providerId = IntegrationId("mangaupdates"),
                    attribution = "MangaUpdates",
                ),
                editorialVolumeCount = ProvenancedMetadata(
                    value = 18,
                    providerId = IntegrationId("mangaupdates"),
                    attribution = "MangaUpdates",
                ),
            ),
        )
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = FakeChapterRepository(emptyList()),
            materializeInferredChapter = mockk(relaxed = true),
            chapterEvidenceRepository = FakeEvidenceRepository(),
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = FakeChapterRepository(emptyList()),
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                },
            ),
            downloadCanonicalChapter = mockk(relaxed = true),
            canonicalDownloadRepository = mockk(relaxed = true),
            reportedChapterCountRepository = FakeReportedChapterCountRepository(),
            addonRepository = FakeAddonRepository(),
            refreshReportedChapterCounts = metadataRefresh(),
            refreshChapterEvidence = RefreshChapterEvidence(
                registry = emptyRegistry(),
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = FakeChapterRepository(emptyList()),
                    evidenceRepository = FakeEvidenceRepository(),
                ),
            ),
            resolveCanonicalMetadata = resolver,
            resolveCanonicalSourceManga = sourceResolver,
            resolveCanonicalArtwork = artworkResolver,
        )

        model.start("title")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
        state.coverUrl shouldBe "https://kitsu.example/dandadan.jpg"
        state.sourceCover?.sourceId shouldBe 10L
        state.sourceCover?.url shouldBe "https://cdn.example/dandadan.jpg"
        state.tags shouldBe listOf("Psychological", "Crime")
        state.editorialStatus shouldBe "COMPLETED"
        state.editorialFormat shouldBe "MANGA"
        state.ratingValue shouldBe 8.72
        state.ratingMaxValue shouldBe 10.0
        state.ratingVoteCount shouldBe 143215
        state.ratings.map { it.providerId } shouldBe listOf("mal", "mangaupdates")
        state.ratings.map { it.value } shouldBe listOf(8.72, 9.12)
        state.ratings.map { it.voteCount } shouldBe listOf(143215, 12345)
        state.startDate shouldBe "1994"
        state.endDate shouldBe "2001"
        state.editorialVolumeCount shouldBe 18
        state.metadataSources shouldBe listOf("MangaUpdates", "MyAnimeList")
        coVerify(exactly = 0) {
            sourceResolver.execute(canonicalTitleId = "title", allowNetwork = true)
        }
    }

    @Test
    fun `detail can add and remove canonical title from Library without replacing title`() = runTest(dispatcher) {
        val library = FakeLibraryRepository()
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = library,
            canonicalChapterRepository = FakeChapterRepository(emptyList()),
            materializeInferredChapter = mockk(relaxed = true),
            chapterEvidenceRepository = FakeEvidenceRepository(),
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = FakeChapterRepository(emptyList()),
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                },
            ),
            downloadCanonicalChapter = mockk<DownloadCanonicalChapter>(relaxed = true),
            canonicalDownloadRepository = mockk<CanonicalDownloadRepository>(relaxed = true),
            reportedChapterCountRepository = FakeReportedChapterCountRepository(),
            addonRepository = FakeAddonRepository(),
            refreshReportedChapterCounts = metadataRefresh(),
            refreshChapterEvidence = RefreshChapterEvidence(
                registry = emptyRegistry(),
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = FakeChapterRepository(emptyList()),
                    evidenceRepository = FakeEvidenceRepository(),
                ),
            ),
            resolveCanonicalMetadata = emptyMetadataResolver(),
            resolveCanonicalSourceManga = mockk(relaxed = true),
            resolveCanonicalArtwork = mockk(relaxed = true),
        )

        model.start("title")
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>().libraryEntry shouldBe null

        model.addToLibrary()
        advanceUntilIdle()

        val added = model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
        added.libraryEntry?.canonicalTitleId shouldBe "title"
        library.get("title")?.canonicalTitleId shouldBe "title"

        model.removeFromLibrary()
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>().libraryEntry shouldBe null
        library.get("title") shouldBe null
    }

    @Test
    fun `detail exposes provisional state without hiding chapter`() = runTest(dispatcher) {
        val chapters = FakeChapterRepository(
            listOf(
                CanonicalChapter(
                    id = "chapter-37",
                    canonicalTitleId = "title",
                    displayNumber = "37",
                    baseNumber = 37,
                    confidence = 1.0,
                    createdAt = 1L,
                    updatedAt = 1L,
                    confirmation = CanonicalChapterConfirmation.PROVISIONAL,
                ),
            ),
        )
        val evidenceRepository = FakeEvidenceRepository(
            listOf(
                PersistedChapterEvidence(
                    evidence = ChapterEvidence(
                        id = "evidence-37",
                        canonicalTitleId = "title",
                        producerKind = ProducerKind.ADDON,
                        producerId = "mangafire",
                        externalChapterKey = "7:/chapter-37",
                        rawLabel = "Chapter 37",
                        rawNumber = 37.0,
                        volume = null,
                        title = null,
                        observedAt = 1L,
                        confidence = 1.0,
                        authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
                    ),
                    mappedCanonicalChapterId = "chapter-37",
                ),
            ),
        )
        val refresh = RefreshChapterEvidence(
            registry = emptyRegistry(),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapters,
                evidenceRepository = evidenceRepository,
            ),
        )
        val diagnostics = RecordingChapterInventoryDiagnostics().apply { start("title") }
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
            materializeInferredChapter = mockk(relaxed = true),
            chapterEvidenceRepository = evidenceRepository,
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = chapters,
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                },
            ),
            downloadCanonicalChapter = mockk<DownloadCanonicalChapter>(relaxed = true),
            canonicalDownloadRepository = mockk<CanonicalDownloadRepository>(relaxed = true),
            reportedChapterCountRepository = FakeReportedChapterCountRepository(),
            addonRepository = FakeAddonRepository(),
            refreshReportedChapterCounts = metadataRefresh(),
            refreshChapterEvidence = refresh,
            diagnostics = diagnostics,
            resolveCanonicalMetadata = emptyMetadataResolver(),
            resolveCanonicalSourceManga = mockk(relaxed = true),
            resolveCanonicalArtwork = mockk(relaxed = true),
        )

        model.start("title")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
        state.chapters.single().confirmation shouldBe CanonicalChapterConfirmation.PROVISIONAL
        state.chapters.single().chapter.id shouldBe "chapter-37"
        state.addonCoverage.single().displayName shouldBe "MangaFire"
        state.addonCoverage.single().firstKnownNumber shouldBe 37
        val uiEvents = diagnostics.events.filter { it.stage == ChapterInventoryDiagnosticStage.UI }
        uiEvents.first().received shouldBe 1
        val uiEvent = uiEvents.last()
        uiEvent.accepted shouldBe 1
        uiEvent.provisional shouldBe 1
        uiEvent.inferred shouldBe 0
    }

    @Test
    fun `canonical artifact keeps offline download visible without source variants`() = runTest(dispatcher) {
        val chapters = FakeChapterRepository(
            listOf(
                CanonicalChapter(
                    id = "chapter-37",
                    canonicalTitleId = "title",
                    displayNumber = "37",
                    baseNumber = 37,
                    confidence = 1.0,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
            ),
        )
        val downloads = mockk<CanonicalDownloadRepository>()
        coEvery {
            downloads.getChapterIdsByCanonicalTitle("title")
        } returns setOf("chapter-37")
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
            materializeInferredChapter = mockk(relaxed = true),
            chapterEvidenceRepository = FakeEvidenceRepository(),
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = chapters,
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                },
            ),
            downloadCanonicalChapter = mockk(relaxed = true),
            canonicalDownloadRepository = downloads,
            reportedChapterCountRepository = FakeReportedChapterCountRepository(),
            addonRepository = FakeAddonRepository(),
            refreshReportedChapterCounts = metadataRefresh(),
            refreshChapterEvidence = RefreshChapterEvidence(
                registry = emptyRegistry(),
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = chapters,
                    evidenceRepository = FakeEvidenceRepository(),
                ),
            ),
            resolveCanonicalMetadata = emptyMetadataResolver(),
            resolveCanonicalSourceManga = mockk(relaxed = true),
            resolveCanonicalArtwork = mockk(relaxed = true),
        )

        model.start("title")
        advanceUntilIdle()

        model.state.value
            .shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
            .chapters
            .single()
            .downloaded shouldBe true
    }

    @Test
    fun `download without preference exposes content selection to detail UI`() = runTest(dispatcher) {
        val chapters = FakeChapterRepository(
            listOf(
                CanonicalChapter(
                    id = "chapter-37",
                    canonicalTitleId = "title",
                    displayNumber = "37",
                    baseNumber = 37,
                    confidence = 1.0,
                    createdAt = 1L,
                    updatedAt = 1L,
                    confirmation = CanonicalChapterConfirmation.CONFIRMED,
                ),
            ),
        )
        val downloader = mockk<DownloadCanonicalChapter>()
        coEvery {
            downloader.execute(
                canonicalChapterId = "chapter-37",
                selectedOption = null,
            )
        } returns CanonicalDownloadPreparation.SelectionRequired(
            canonicalTitleId = "title",
            canonicalChapterId = "chapter-37",
            options = emptyList(),
            preferredAddonId = null,
        )
        val downloads = mockk<CanonicalDownloadRepository>()
        coEvery { downloads.getChapterIdsByCanonicalTitle("title") } returns emptySet()
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
            materializeInferredChapter = mockk(relaxed = true),
            chapterEvidenceRepository = FakeEvidenceRepository(),
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = chapters,
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                },
            ),
            downloadCanonicalChapter = downloader,
            canonicalDownloadRepository = downloads,
            reportedChapterCountRepository = FakeReportedChapterCountRepository(),
            addonRepository = FakeAddonRepository(),
            refreshReportedChapterCounts = metadataRefresh(),
            refreshChapterEvidence = RefreshChapterEvidence(
                registry = emptyRegistry(),
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = chapters,
                    evidenceRepository = FakeEvidenceRepository(),
                ),
            ),
            resolveCanonicalMetadata = emptyMetadataResolver(),
            resolveCanonicalSourceManga = mockk(relaxed = true),
            resolveCanonicalArtwork = mockk(relaxed = true),
        )

        model.start("title")
        advanceUntilIdle()
        model.requestDownload("chapter-37")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
        state.downloadSelectionChapterId shouldBe "chapter-37"
        state.downloadInProgressChapterId shouldBe null
    }

    @Test
    fun `detail does not probe every legacy download while rendering canonical chapters`() = runTest(dispatcher) {
        val chapterList = (1..24).map { index ->
            CanonicalChapter(
                id = "chapter-$index",
                canonicalTitleId = "title",
                displayNumber = index.toString(),
                baseNumber = index,
                confidence = 1.0,
                createdAt = 1L,
                updatedAt = 1L,
                confirmation = CanonicalChapterConfirmation.CONFIRMED,
            )
        }
        val chapterRepo = FakeChapterRepository(
            chapterList,
            variants = chapterList.associate { chapter ->
                chapter.id to listOf(
                    ChapterVariant(
                        id = "variant-${chapter.id}",
                        canonicalChapterId = chapter.id,
                        sourceId = 7L,
                        sourceChapterId = chapter.id,
                    ),
                )
            },
        )
        var legacyChecks = 0
        val downloads = mockk<CanonicalDownloadRepository>()
        coEvery { downloads.getChapterIdsByCanonicalTitle("title") } returns emptySet()
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapterRepo,
            materializeInferredChapter = mockk(relaxed = true),
            chapterEvidenceRepository = FakeEvidenceRepository(),
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = chapterRepo,
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean {
                        legacyChecks++
                        return false
                    }
                },
            ),
            downloadCanonicalChapter = mockk(relaxed = true),
            canonicalDownloadRepository = downloads,
            reportedChapterCountRepository = FakeReportedChapterCountRepository(),
            addonRepository = FakeAddonRepository(),
            refreshReportedChapterCounts = metadataRefresh(),
            refreshChapterEvidence = RefreshChapterEvidence(
                registry = emptyRegistry(),
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = chapterRepo,
                    evidenceRepository = FakeEvidenceRepository(),
                ),
            ),
            resolveCanonicalMetadata = emptyMetadataResolver(),
            resolveCanonicalSourceManga = mockk(relaxed = true),
            resolveCanonicalArtwork = mockk(relaxed = true),
        )

        model.start("title")
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
            .chapters.size shouldBe 24
        legacyChecks shouldBe 0
    }

    @Test
    fun `unsupported provisional artifact is hidden without progress or download`() = runTest(dispatcher) {
        val chapters = FakeChapterRepository(
            listOf(
                CanonicalChapter(
                    id = "stale-9-46",
                    canonicalTitleId = "title",
                    displayNumber = "9.46",
                    baseNumber = 9,
                    part = 46,
                    confidence = 0.8,
                    createdAt = 1L,
                    updatedAt = 1L,
                    confirmation = CanonicalChapterConfirmation.PROVISIONAL,
                ),
            ),
        )
        val downloads = mockk<CanonicalDownloadRepository>()
        coEvery { downloads.getChapterIdsByCanonicalTitle("title") } returns emptySet()
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
            materializeInferredChapter = mockk(relaxed = true),
            chapterEvidenceRepository = FakeEvidenceRepository(),
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = chapters,
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                },
            ),
            downloadCanonicalChapter = mockk(relaxed = true),
            canonicalDownloadRepository = downloads,
            reportedChapterCountRepository = FakeReportedChapterCountRepository(),
            addonRepository = FakeAddonRepository(),
            refreshReportedChapterCounts = metadataRefresh(),
            refreshChapterEvidence = RefreshChapterEvidence(
                registry = emptyRegistry(),
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = ParseCanonicalChapterLabel(),
                    canonicalChapterRepository = chapters,
                    evidenceRepository = FakeEvidenceRepository(),
                ),
            ),
            resolveCanonicalMetadata = emptyMetadataResolver(),
            resolveCanonicalSourceManga = mockk(relaxed = true),
            resolveCanonicalArtwork = mockk(relaxed = true),
        )

        model.start("title")
        advanceUntilIdle()

        model.state.value
            .shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
            .chapters shouldBe emptyList()
    }

    @Test
    fun `Kitsu count produces unified rows before source discovery and selected slot keeps identity`() =
        runTest(dispatcher) {
            val chapters = FakeChapterRepository(emptyList())
            val reported = FakeReportedChapterCountRepository(
                listOf(ReportedChapterCount("title", "kitsu", 108, 1L)),
            )
            val downloads = mockk<CanonicalDownloadRepository>()
            coEvery { downloads.getChapterIdsByCanonicalTitle("title") } returns emptySet()
            val diagnostics = RecordingChapterInventoryDiagnostics().apply { start("title") }
            val materializer = MaterializeInferredChapter(chapters, ChapterMutationGate())
            val model = CanonicalTitleScreenModel(
                canonicalTitleRepository = FakeTitleRepository(),
                canonicalLibraryRepository = FakeLibraryRepository(),
                canonicalChapterRepository = chapters,
                materializeInferredChapter = materializer,
                chapterEvidenceRepository = FakeEvidenceRepository(),
                canonicalReadingRepository = FakeReadingRepository(),
                getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                    canonicalChapterRepository = chapters,
                    canonicalDownloadGateway = object : CanonicalDownloadGateway {
                        override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                    },
                ),
                downloadCanonicalChapter = mockk(relaxed = true),
                canonicalDownloadRepository = downloads,
                reportedChapterCountRepository = reported,
                addonRepository = FakeAddonRepository(),
                refreshReportedChapterCounts = metadataRefresh(reported),
                refreshChapterEvidence = RefreshChapterEvidence(
                    registry = emptyRegistry(),
                    reconcileChapterEvidence = ReconcileChapterEvidence(
                        parser = ParseCanonicalChapterLabel(),
                        canonicalChapterRepository = chapters,
                        evidenceRepository = FakeEvidenceRepository(),
                    ),
                ),
                diagnostics = diagnostics,
                resolveCanonicalMetadata = emptyMetadataResolver(),
                resolveCanonicalSourceManga = mockk(relaxed = true),
                resolveCanonicalArtwork = mockk(relaxed = true),
            )
            model.start("title")
            advanceUntilIdle()

            val current = model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
            current.chapters.size shouldBe 108
            current.chapters.first().chapter.displayNumber shouldBe "1"
            current.chapters.last().chapter.displayNumber shouldBe "108"
            current.chapters.all(CanonicalChapterDetailItem::inferredFromCount) shouldBe true
            chapters.getByCanonicalTitleId("title") shouldBe emptyList()
            val uiEvents = diagnostics.events.filter { it.stage == ChapterInventoryDiagnosticStage.UI }
            uiEvents.first().received shouldBe 0
            val uiEvent = uiEvents.last()
            uiEvent.accepted shouldBe 0
            uiEvent.inferred shouldBe 108
            uiEvent.discarded shouldBe 0

            val opened = mutableListOf<String>()
            model.openChapter(inferredChapterId("title", 37), opened::add)
            advanceUntilIdle()
            opened shouldBe listOf(inferredChapterId("title", 37))
            chapters.getByCanonicalTitleId("title").single().id shouldBe opened.single()

            model.openChapter(inferredChapterId("title", 37), opened::add)
            advanceUntilIdle()
            opened.size shouldBe 2
            chapters.getByCanonicalTitleId("title").size shouldBe 1
        }

    private class FakeAddonRepository : AddonRepository {
        private val addons = listOf(
            InstalledAddon(
                id = AddonId("mangafire"),
                displayName = "MangaFire",
                enabled = true,
                versionName = "1.0",
                mihonSourceIds = emptyList(),
                hasSettings = false,
            ),
        )

        override fun observeInstalled(): Flow<List<InstalledAddon>> = MutableStateFlow(addons)
        override suspend fun snapshot(): List<InstalledAddon> = addons
        override suspend fun setEnabled(id: AddonId, enabled: Boolean) = Unit
    }

    private fun metadataRefresh(
        repository: ReportedChapterCountRepository = FakeReportedChapterCountRepository(),
    ) = RefreshReportedChapterCounts(
        canonicalTitleRepository = FakeTitleRepository(),
        registry = emptyRegistry(),
        repository = repository,
    )

    private class FakeReportedChapterCountRepository(
        initial: List<ReportedChapterCount> = emptyList(),
    ) : ReportedChapterCountRepository {
        private val values = initial.associateByTo(linkedMapOf()) { it.provider }

        override suspend fun getByTitle(canonicalTitleId: String): List<ReportedChapterCount> =
            values.values.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(value: ReportedChapterCount) {
            values[value.provider] = value
        }
    }

    private fun emptyRegistry() = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = emptyList()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }

    private class FakeTitleRepository : CanonicalTitleRepository {
        private val title = CanonicalTitle(
            id = "title",
            displayTitle = "Title",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1L,
            updatedAt = 1L,
        )

        override suspend fun getById(id: String): CanonicalTitle? = title.takeIf { it.id == id }
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> =
            MutableStateFlow(title.takeIf { it.id == id })
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = title
        override suspend fun insert(title: CanonicalTitle) = Unit
        override suspend fun addExternalIdentity(identity: ExternalIdentity) = Unit
    }

    private class FakeLibraryRepository : CanonicalLibraryRepository {
        private val entries = linkedMapOf<String, CanonicalLibraryEntry>()

        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? = entries[canonicalTitleId]
        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> =
            MutableStateFlow(entries.values.toList())
        override fun getAllItemsAsFlow(): Flow<List<LibraryTitle>> = MutableStateFlow(emptyList())
        override suspend fun upsert(entry: CanonicalLibraryEntry) {
            entries[entry.canonicalTitleId] = entry
        }
        override suspend fun remove(canonicalTitleId: String) {
            entries.remove(canonicalTitleId)
        }
    }

    private class FakeReadingRepository : CanonicalReadingRepository {
        override suspend fun getProgress(canonicalChapterId: String): CanonicalChapterProgress? = null
        override fun observeProgress(
            canonicalChapterId: String,
        ): Flow<CanonicalChapterProgress?> = MutableStateFlow(null)
        override suspend fun getProgressByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapterProgress> =
            emptyList()
        override suspend fun upsertProgress(progress: CanonicalChapterProgress) = Unit
        override suspend fun getHistory(canonicalChapterId: String): CanonicalChapterHistory? = null
        override suspend fun recordHistory(update: CanonicalChapterHistoryUpdate) = Unit
        override suspend fun recordCheckpoint(
            progress: CanonicalChapterProgress,
            history: CanonicalChapterHistoryUpdate?,
        ) = Unit
    }

    private fun emptyMetadataResolver(): ResolveCanonicalMetadata = mockk {
        coEvery { cached(any()) } returns null
        coEvery { execute(any(), any()) } returns Result.success(ResolvedMetadata())
    }

    private class FakeEvidenceRepository(
        initial: List<PersistedChapterEvidence> = emptyList(),
    ) : ChapterEvidenceRepository {
        private val records = initial.toMutableList()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<PersistedChapterEvidence> =
            records.filter { it.evidence.canonicalTitleId == canonicalTitleId }

        override suspend fun getByProducerExternalKey(
            producerKind: ProducerKind,
            producerId: String,
            externalChapterKey: String,
        ): PersistedChapterEvidence? = records.firstOrNull {
            it.evidence.producerKind == producerKind &&
                it.evidence.producerId == producerId &&
                it.evidence.externalChapterKey == externalChapterKey
        }

        override suspend fun upsert(
            evidence: ChapterEvidence,
            mappedCanonicalChapterId: String?,
        ): PersistedChapterEvidence {
            val persisted = PersistedChapterEvidence(evidence, mappedCanonicalChapterId)
            val index = records.indexOfFirst { it.evidence.id == evidence.id }
            if (index >= 0) {
                records[index] = persisted
            } else {
                records += persisted
            }
            return persisted
        }
    }

    private class FakeChapterRepository(
        initial: List<CanonicalChapter>,
        private val variants: Map<String, List<ChapterVariant>> = emptyMap(),
    ) : CanonicalChapterRepository {
        private val chapters = initial.associateByTo(linkedMapOf()) { it.id }

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.values.filter { it.canonicalTitleId == canonicalTitleId }
        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.values.filter { it.canonicalTitleId == canonicalTitleId })
        override suspend fun getById(id: String): CanonicalChapter? = chapters[id]
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? = null
        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            variants[canonicalChapterId].orEmpty()
        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> = emptyList()
        override suspend fun upsert(chapter: CanonicalChapter) {
            chapters[chapter.id] = chapter
        }
        override suspend fun upsertVariant(variant: ChapterVariant) = Unit
        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) {
            chapters.forEach { upsert(it) }
        }
    }
}
