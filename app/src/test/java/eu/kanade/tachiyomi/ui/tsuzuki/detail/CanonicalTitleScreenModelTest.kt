package eu.kanade.tachiyomi.ui.tsuzuki.detail

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
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
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
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
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
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
    fun `detail can add and remove canonical title from Library without replacing title`() = runTest(dispatcher) {
        val library = FakeLibraryRepository()
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = library,
            canonicalChapterRepository = FakeChapterRepository(emptyList()),
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
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
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
        )

        model.start("title")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
        state.chapters.single().confirmation shouldBe CanonicalChapterConfirmation.PROVISIONAL
        state.chapters.single().chapter.id shouldBe "chapter-37"
        state.addonCoverage.single().displayName shouldBe "MangaFire"
        state.addonCoverage.single().firstKnownNumber shouldBe 37
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
        coEvery { downloads.getAll() } returns listOf(
            CanonicalDownloadArtifact(
                canonicalChapterId = "chapter-37",
                localUri = "content://downloads/chapter-37",
                format = "DIRECTORY",
                originatingAddonId = null,
                originatingOptionKey = null,
                completedAt = 100L,
                checksum = null,
            ),
        )
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
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
        coEvery { downloads.getAll() } returns emptyList()
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
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
    fun `detail bounds legacy download checks for large chapter lists`() = runTest(dispatcher) {
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
        val releaseChecks = CompletableDeferred<Unit>()
        var concurrent = 0
        var peak = 0
        val downloads = mockk<CanonicalDownloadRepository>()
        coEvery { downloads.getAll() } returns emptyList()
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapterRepo,
            chapterEvidenceRepository = FakeEvidenceRepository(),
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = chapterRepo,
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean {
                        concurrent++
                        peak = maxOf(peak, concurrent)
                        try {
                            releaseChecks.await()
                            return false
                        } finally {
                            concurrent--
                        }
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
        )

        model.start("title")
        runCurrent()
        model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
            .chapters.size shouldBe 24
        peak shouldBe 8

        releaseChecks.complete(Unit)
        advanceUntilIdle()
        concurrent shouldBe 0
        model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
            .chapters.size shouldBe 24
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
        coEvery { downloads.getAll() } returns emptyList()
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
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
        )

        model.start("title")
        advanceUntilIdle()

        model.state.value
            .shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
            .chapters shouldBe emptyList()
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
