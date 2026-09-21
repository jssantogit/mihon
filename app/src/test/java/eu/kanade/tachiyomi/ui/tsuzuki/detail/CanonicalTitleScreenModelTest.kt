package eu.kanade.tachiyomi.ui.tsuzuki.detail

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.download.interactor.GetCanonicalChapterDownloadState
import tachiyomi.domain.tsuzuki.download.service.CanonicalDownloadGateway
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
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
        val refresh = RefreshChapterEvidence(
            registry = emptyRegistry(),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapters,
                evidenceRepository = FakeEvidenceRepository(),
            ),
        )
        val model = CanonicalTitleScreenModel(
            canonicalTitleRepository = FakeTitleRepository(),
            canonicalLibraryRepository = FakeLibraryRepository(),
            canonicalChapterRepository = chapters,
            canonicalReadingRepository = FakeReadingRepository(),
            getCanonicalChapterDownloadState = GetCanonicalChapterDownloadState(
                canonicalChapterRepository = chapters,
                canonicalDownloadGateway = object : CanonicalDownloadGateway {
                    override suspend fun isDownloaded(variant: ChapterVariant): Boolean = false
                },
            ),
            refreshChapterEvidence = refresh,
        )

        model.start("title")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<CanonicalTitleScreenState.Loaded>()
        state.chapters.single().confirmation shouldBe CanonicalChapterConfirmation.PROVISIONAL
        state.chapters.single().chapter.id shouldBe "chapter-37"
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
        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? = null
        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(emptyList())
        override fun getAllItemsAsFlow(): Flow<List<LibraryTitle>> = MutableStateFlow(emptyList())
        override suspend fun upsert(entry: CanonicalLibraryEntry) = Unit
        override suspend fun remove(canonicalTitleId: String) = Unit
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

    private class FakeEvidenceRepository : ChapterEvidenceRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<PersistedChapterEvidence> =
            emptyList()
        override suspend fun getByProducerExternalKey(
            producerKind: ProducerKind,
            producerId: String,
            externalChapterKey: String,
        ): PersistedChapterEvidence? = null
        override suspend fun upsert(
            evidence: ChapterEvidence,
            mappedCanonicalChapterId: String?,
        ): PersistedChapterEvidence = PersistedChapterEvidence(evidence, mappedCanonicalChapterId)
    }

    private class FakeChapterRepository(
        initial: List<CanonicalChapter>,
    ) : CanonicalChapterRepository {
        private val chapters = initial.associateByTo(linkedMapOf()) { it.id }

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.values.filter { it.canonicalTitleId == canonicalTitleId }
        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.values.filter { it.canonicalTitleId == canonicalTitleId })
        override suspend fun getById(id: String): CanonicalChapter? = chapters[id]
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? = null
        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            emptyList()
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
