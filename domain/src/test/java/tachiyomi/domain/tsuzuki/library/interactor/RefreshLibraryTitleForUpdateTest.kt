package tachiyomi.domain.tsuzuki.library.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
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
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.updates.interactor.RecordNewCanonicalChapters
import tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateState
import tachiyomi.domain.tsuzuki.updates.repository.ChapterUpdateStateRepository

class RefreshLibraryTitleForUpdateTest {

    @Test
    fun `library update refreshes integration evidence with zero source mappings and records new chapter`() = runTest {
        val chapters = FakeCanonicalChapterRepository()
        val evidenceRepository = FakeChapterEvidenceRepository()
        val updateStateRepository = FakeChapterUpdateStateRepository()
        val libraryRepository = FakeLibraryRepository()
        val provider = object : ChapterEvidenceProvider {
            override val producerId: String = "mal"

            override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> {
                return Result.success(
                    listOf(
                        ChapterEvidence(
                            id = "mal-1",
                            canonicalTitleId = canonicalTitleId,
                            producerKind = ProducerKind.INTEGRATION,
                            producerId = producerId,
                            externalChapterKey = "1",
                            rawLabel = "Chapter 1",
                            rawNumber = null,
                            volume = null,
                            title = null,
                            observedAt = 10L,
                            confidence = 1.0,
                            authority = ChapterEvidenceAuthority.EDITORIAL,
                        ),
                    ),
                )
            }
        }
        var nextId = 0
        val refreshEvidence = RefreshChapterEvidence(
            registry = registry(provider),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapters,
                evidenceRepository = evidenceRepository,
                idFactory = { "chapter-${++nextId}" },
                clock = { 100L },
            ),
        )
        val recordUpdates = RecordNewCanonicalChapters(
            canonicalChapterRepository = chapters,
            canonicalLibraryRepository = libraryRepository,
            chapterUpdateStateRepository = updateStateRepository,
            clock = { 200L },
        )

        val result = RefreshLibraryTitleForUpdate(
            refreshChapterEvidence = refreshEvidence,
            recordNewCanonicalChapters = recordUpdates,
        ).execute(libraryTitle())

        result.isSuccess shouldBe true
        result.getOrThrow() shouldBe null
        chapters.getByCanonicalTitleId("title-1")
            .map { it.displayNumber } shouldContainExactly listOf("1")
        chapters.getByCanonicalTitleId("title-1").single().confirmation shouldBe
            CanonicalChapterConfirmation.CONFIRMED
        updateStateRepository.getByCanonicalTitleId("title-1") shouldContainExactly listOf(
            ChapterUpdateState(
                canonicalChapterId = "chapter-1",
                canonicalTitleId = "title-1",
                firstSeenAt = 200L,
                acknowledgedAt = null,
            ),
        )
    }

    private fun libraryTitle() = LibraryTitle(
        title = CanonicalTitle(
            id = "title-1",
            displayTitle = "Title",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 100L,
            updatedAt = 100L,
        ),
        entry = CanonicalLibraryEntry(
            canonicalTitleId = "title-1",
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 100L,
            updatedAt = 100L,
        ),
        sources = emptyList(),
    )

    private fun registry(provider: ChapterEvidenceProvider) = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = emptyList()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = listOf(provider)
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }

    private class FakeLibraryRepository : CanonicalLibraryRepository {
        private val entry = CanonicalLibraryEntry(
            canonicalTitleId = "title-1",
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 100L,
            updatedAt = 100L,
        )

        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? =
            entry.takeIf { it.canonicalTitleId == canonicalTitleId }

        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(listOf(entry))

        override fun getAllItemsAsFlow(): Flow<List<LibraryTitle>> = MutableStateFlow(emptyList())

        override suspend fun upsert(entry: CanonicalLibraryEntry) = Unit

        override suspend fun remove(canonicalTitleId: String) = Unit
    }

    private class FakeChapterUpdateStateRepository : ChapterUpdateStateRepository {
        private val states = linkedMapOf<String, ChapterUpdateState>()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<ChapterUpdateState> =
            states.values.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun getUnacknowledgedByCanonicalTitleId(
            canonicalTitleId: String,
        ): List<ChapterUpdateState> =
            getByCanonicalTitleId(canonicalTitleId).filter { it.acknowledgedAt == null }

        override suspend fun upsert(state: ChapterUpdateState) {
            states[state.canonicalChapterId] = state
        }

        override suspend fun acknowledge(canonicalChapterId: String, acknowledgedAt: Long) {
            states[canonicalChapterId]?.let { state ->
                states[canonicalChapterId] = state.copy(acknowledgedAt = acknowledgedAt)
            }
        }

        override suspend fun delete(canonicalChapterId: String) {
            states.remove(canonicalChapterId)
        }
    }

    private class FakeChapterEvidenceRepository : ChapterEvidenceRepository {
        private val records = mutableListOf<PersistedChapterEvidence>()

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
            records.removeAll { it.evidence.id == evidence.id }
            records += persisted
            return persisted
        }
    }

    private class FakeCanonicalChapterRepository : CanonicalChapterRepository {
        private val chapters = linkedMapOf<String, CanonicalChapter>()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.values.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? = chapters[id]

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = null

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            emptyList()

        override suspend fun upsert(chapter: CanonicalChapter) {
            chapters[chapter.id] = chapter
        }

        override suspend fun upsertVariant(variant: ChapterVariant) = Unit

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) {
            chapters.forEach { upsert(it) }
        }
    }
}
