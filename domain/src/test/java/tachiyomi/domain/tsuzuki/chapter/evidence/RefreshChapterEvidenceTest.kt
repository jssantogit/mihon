package tachiyomi.domain.tsuzuki.chapter.evidence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
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

class RefreshChapterEvidenceTest {

    @Test
    fun `chapter evidence refresh works with zero source mappings`() = runTest {
        val chapters = FakeCanonicalChapterRepository()
        val refresh = refresh(
            chapters = chapters,
            providers = listOf(
                provider(
                    "editorial",
                    Result.success(
                        listOf(
                            editorialEvidence("e1", "1", "Chapter 1"),
                            editorialEvidence("e2", "2", "Chapter 2"),
                        ),
                    ),
                ),
            ),
        )

        val result = refresh.execute("canonical-title")

        result.isSuccess shouldBe true
        chapters.getByCanonicalTitleId("canonical-title")
            .map { it.displayNumber } shouldContainExactly listOf("1", "2")
    }

    @Test
    fun `one evidence provider failure does not erase successful provider evidence`() = runTest {
        val chapters = FakeCanonicalChapterRepository()
        val refresh = refresh(
            chapters = chapters,
            providers = listOf(
                provider("broken", Result.failure(IllegalStateException("offline"))),
                provider(
                    "healthy",
                    Result.success(listOf(editorialEvidence("e3", "3", "Chapter 3", "healthy"))),
                ),
            ),
        )

        refresh.execute("canonical-title").isSuccess shouldBe true

        chapters.getByCanonicalTitleId("canonical-title")
            .map { it.displayNumber } shouldContainExactly listOf("3")
    }

    @Test
    fun `no enabled provider evidence keeps existing chapter graph unchanged`() = runTest {
        val chapters = FakeCanonicalChapterRepository(
            initial = listOf(
                CanonicalChapter(
                    id = "existing",
                    canonicalTitleId = "canonical-title",
                    displayNumber = "9",
                    createdAt = 1L,
                    updatedAt = 1L,
                    confirmation = CanonicalChapterConfirmation.CONFIRMED,
                ),
            ),
        )
        val refresh = refresh(chapters = chapters, providers = emptyList())

        refresh.execute("canonical-title").isSuccess shouldBe true

        chapters.getByCanonicalTitleId("canonical-title")
            .map { it.id } shouldContainExactly listOf("existing")
    }

    @Test
    fun `refresh waits for integration registry readiness before reading providers`() = runTest {
        var ready = false
        val provider = object : ChapterEvidenceProvider {
            override val producerId: String = "ready-check"

            override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> {
                ready shouldBe true
                return Result.success(emptyList())
            }
        }
        val registry = object : IntegrationRegistry {
            override suspend fun awaitReady() {
                ready = true
            }

            override fun searchProviders(): List<SearchProvider> = emptyList()
            override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
            override fun metadataProviders(): List<MetadataProvider> = emptyList()
            override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = listOf(provider)
            override fun ratingsProviders(): List<RatingsProvider> = emptyList()
            override fun trackingProviders(): List<TrackingProvider> = emptyList()
        }
        val chapters = FakeCanonicalChapterRepository()
        val refresh = RefreshChapterEvidence(
            registry = registry,
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapters,
                evidenceRepository = FakeChapterEvidenceRepository(),
                idFactory = { "unused" },
                clock = { 100L },
            ),
        )

        refresh.execute("canonical-title").isSuccess shouldBe true
        ready shouldBe true
    }

    @Test
    fun `caller cancellation is never swallowed as provider failure`() = runTest {
        val cancelling = object : ChapterEvidenceProvider {
            override val producerId: String = "cancel"

            override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> {
                throw CancellationException("cancelled")
            }
        }
        val refresh = refresh(
            chapters = FakeCanonicalChapterRepository(),
            providers = listOf(cancelling),
        )

        shouldThrow<CancellationException> {
            refresh.execute("canonical-title")
        }
    }

    private fun refresh(
        chapters: FakeCanonicalChapterRepository,
        providers: List<ChapterEvidenceProvider>,
    ): RefreshChapterEvidence {
        val evidence = FakeChapterEvidenceRepository()
        var id = 0
        return RefreshChapterEvidence(
            registry = registry(providers),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapters,
                evidenceRepository = evidence,
                idFactory = { "chapter-${++id}" },
                clock = { 100L },
            ),
        )
    }

    private fun provider(
        id: String,
        result: Result<List<ChapterEvidence>>,
    ) = object : ChapterEvidenceProvider {
        override val producerId: String = id

        override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> = result
    }

    private fun editorialEvidence(
        id: String,
        externalKey: String,
        rawLabel: String,
        producerId: String = "editorial",
    ) = ChapterEvidence(
        id = id,
        canonicalTitleId = "canonical-title",
        producerKind = ProducerKind.INTEGRATION,
        producerId = producerId,
        externalChapterKey = externalKey,
        rawLabel = rawLabel,
        rawNumber = null,
        volume = null,
        title = null,
        observedAt = 10L,
        confidence = 1.0,
        authority = ChapterEvidenceAuthority.EDITORIAL,
    )

    private fun registry(
        providers: List<ChapterEvidenceProvider>,
    ) = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = emptyList()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = providers
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
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
            val existing = evidence.externalChapterKey?.let { key ->
                records.indexOfFirst {
                    it.evidence.producerKind == evidence.producerKind &&
                        it.evidence.producerId == evidence.producerId &&
                        it.evidence.externalChapterKey == key
                }
            } ?: -1
            val persisted = PersistedChapterEvidence(evidence, mappedCanonicalChapterId)
            if (existing >= 0) records[existing] = persisted else records += persisted
            return persisted
        }
    }

    private class FakeCanonicalChapterRepository(
        initial: List<CanonicalChapter> = emptyList(),
    ) : CanonicalChapterRepository {
        private val chapters = linkedMapOf<String, CanonicalChapter>().apply {
            initial.forEach { put(it.id, it) }
        }

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
