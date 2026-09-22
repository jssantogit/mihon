package tachiyomi.domain.tsuzuki.chapter.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.metadata.ReportedChapterCount

class MaterializeInferredChapterTest {

    @Test
    fun `concurrent read and download taps persist only one canonical chapter`() = runTest {
        val repo = FakeChapters()
        val materializer = MaterializeInferredChapter(repo, ChapterMutationGate(), { 2L })
        val slot = buildCanonicalChapterOutline(
            "title",
            emptyList(),
            listOf(ReportedChapterCount("title", "kitsu", 37, 1L)),
        ).last().chapter
        val chapters = (1..20).map { async { materializer.execute(slot) } }.awaitAll()

        chapters.map(CanonicalChapter::id).distinct() shouldBe listOf(inferredChapterId("title", 37))
        repo.getByCanonicalTitleId("title").size shouldBe 1
        repo.getByCanonicalTitleId("title").single().createdAt shouldBe 2L
    }

    @Test
    fun `arrival of real Add-on evidence reuses the count-only chapter and upgrades confidence`() = runTest {
        val repo = FakeChapters()
        val evidenceRepo = FakeEvidence()
        val sharedGate = ChapterMutationGate()
        val materializer = MaterializeInferredChapter(repo, sharedGate, { 2L })
        val reconciler = ReconcileChapterEvidence(
            parser = ParseCanonicalChapterLabel(),
            canonicalChapterRepository = repo,
            evidenceRepository = evidenceRepo,
            idFactory = { "other-id" },
            clock = { 3L },
            mutationGate = sharedGate,
        )
        val slot = buildCanonicalChapterOutline(
            "title",
            emptyList(),
            listOf(ReportedChapterCount("title", "kitsu", 108, 1L)),
        )[36].chapter
        materializer.execute(slot)
        reconciler.execute(
            "title",
            listOf(
                ChapterEvidence(
                    id = "addon-37",
                    canonicalTitleId = "title",
                    producerKind = ProducerKind.ADDON,
                    producerId = "mangafire",
                    externalChapterKey = "7:/37",
                    rawLabel = "Chapter 37",
                    rawNumber = 37.0,
                    volume = null,
                    title = null,
                    observedAt = 3L,
                    confidence = 1.0,
                    authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
                ),
            ),
        )

        val actual = repo.getByCanonicalTitleId("title").single()
        actual.id shouldBe inferredChapterId("title", 37)
        actual.confidence shouldBe 1.0
        evidenceRepo.getByCanonicalTitleId("title").single().mappedCanonicalChapterId shouldBe actual.id
    }

    @Test
    fun `materializing an already observed identity preserves its existing progress key`() = runTest {
        val repo = FakeChapters()
        val existing = CanonicalChapter(
            id = "existing-37",
            canonicalTitleId = "title",
            displayNumber = "37",
            type = tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType.REGULAR,
            baseNumber = 37,
            confidence = 1.0,
        )
        repo.upsert(existing)
        val slot = buildCanonicalChapterOutline(
            "title",
            emptyList(),
            listOf(ReportedChapterCount("title", "kitsu", 108, 1L)),
        )[36].chapter
        val actual = MaterializeInferredChapter(repo, ChapterMutationGate(), { 2L }).execute(slot)

        actual.id shouldBe existing.id
        repo.getByCanonicalTitleId("title").size shouldBe 1
    }

    private class FakeChapters : CanonicalChapterRepository {
        private val values = linkedMapOf<String, CanonicalChapter>()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) =
            values.values.filter { it.canonicalTitleId == canonicalTitleId }
        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            flowOf(values.values.filter { it.canonicalTitleId == canonicalTitleId })
        override suspend fun getById(id: String): CanonicalChapter? = values[id]
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? = null
        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            emptyList()
        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            emptyList()
        override suspend fun upsert(chapter: CanonicalChapter) {
            values[chapter.id] = chapter
        }
        override suspend fun upsertVariant(variant: ChapterVariant) = Unit
        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) {
            chapters.forEach { upsert(it) }
        }
    }

    private class FakeEvidence : ChapterEvidenceRepository {
        private val values = linkedMapOf<String, PersistedChapterEvidence>()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) =
            values.values.filter { it.evidence.canonicalTitleId == canonicalTitleId }
        override suspend fun getByProducerExternalKey(
            producerKind: ProducerKind,
            producerId: String,
            externalChapterKey: String,
        ): PersistedChapterEvidence? = values.values.firstOrNull {
            it.evidence.producerKind == producerKind &&
                it.evidence.producerId == producerId &&
                it.evidence.externalChapterKey == externalChapterKey
        }
        override suspend fun upsert(
            evidence: ChapterEvidence,
            mappedCanonicalChapterId: String?,
        ): PersistedChapterEvidence = PersistedChapterEvidence(evidence, mappedCanonicalChapterId).also {
            values[evidence.id] = it
        }
    }
}
