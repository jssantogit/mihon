package tachiyomi.domain.tsuzuki.chapter.evidence

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository

class ReconcileChapterEvidenceTest {

    @Test
    fun `addon evidence creates provisional chapter`() = runTest {
        val fixture = fixture()

        fixture.reconciler.execute(
            "title",
            listOf(fixture.addonEvidence(rawLabel = "Chapter 211", externalKey = "addon-211")),
        )

        val chapter = fixture.chapterRepository.getByCanonicalTitleId("title").single()
        chapter.displayNumber shouldBe "211"
        chapter.confirmation shouldBe CanonicalChapterConfirmation.PROVISIONAL
    }

    @Test
    fun `editorial evidence promotes matching provisional chapter without changing its id`() = runTest {
        val fixture = fixture()

        fixture.reconciler.execute(
            "title",
            listOf(fixture.addonEvidence(rawLabel = "Chapter 211", externalKey = "addon-211")),
        )
        val provisionalId = fixture.chapterRepository.getByCanonicalTitleId("title").single().id

        fixture.reconciler.execute(
            "title",
            listOf(fixture.editorialEvidence(rawLabel = "Chapter 211", externalKey = "mal-211")),
        )

        val chapter = fixture.chapterRepository.getByCanonicalTitleId("title").single()
        chapter.id shouldBe provisionalId
        chapter.confirmation shouldBe CanonicalChapterConfirmation.CONFIRMED
    }

    @Test
    fun `chapter count without evidence creates no canonical chapter rows`() = runTest {
        val fixture = fixture()

        fixture.reconciler.execute("title", emptyList())

        fixture.chapterRepository.getByCanonicalTitleId("title") shouldHaveSize 0
    }

    @Test
    fun `ambiguous provisional decimal evidence does not create canonical structure`() = runTest {
        val fixture = fixture()

        fixture.reconciler.execute(
            "title",
            listOf(
                fixture.addonEvidence(
                    rawLabel = "Chapter 9.46",
                    externalKey = "suspicious-9-46",
                ),
            ),
        )

        fixture.chapterRepository.getByCanonicalTitleId("title") shouldHaveSize 0
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "suspicious-9-46",
        )?.mappedCanonicalChapterId shouldBe null
    }

    @Test
    fun `decimal and extra evidence remain distinct logical chapters`() = runTest {
        val fixture = fixture()

        fixture.reconciler.execute(
            "title",
            listOf(
                fixture.addonEvidence(
                    id = "decimal-observation",
                    rawLabel = "Chapter 12.5",
                    externalKey = "decimal",
                ),
                fixture.addonEvidence(
                    id = "extra-observation",
                    rawLabel = "Extra 12",
                    externalKey = "extra",
                ),
            ),
        )

        val chapters = fixture.chapterRepository.getByCanonicalTitleId("title")
        chapters shouldHaveSize 2
        chapters.map { it.id }.distinct() shouldHaveSize 2
        chapters.map { it.displayNumber }.toSet() shouldBe setOf("12.5", "Extra 12")
    }

    @Test
    fun `exact external evidence identity reuses its mapped chapter`() = runTest {
        val fixture = fixture()

        fixture.reconciler.execute(
            "title",
            listOf(fixture.addonEvidence(rawLabel = "Chapter 37", externalKey = "stable-37")),
        )
        val original = fixture.chapterRepository.getByCanonicalTitleId("title").single()

        fixture.reconciler.execute(
            "title",
            listOf(
                fixture.addonEvidence(
                    id = "observation-2",
                    rawLabel = "Chapter 37 - Revised title",
                    externalKey = "stable-37",
                ),
            ),
        )

        val chapters = fixture.chapterRepository.getByCanonicalTitleId("title")
        chapters shouldHaveSize 1
        chapters.single().id shouldBe original.id
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "stable-37",
        )?.mappedCanonicalChapterId shouldBe original.id
    }

    @Test
    fun `incompatible high confidence reuse of one external key marks chapter conflicted`() = runTest {
        val fixture = fixture()

        fixture.reconciler.execute(
            "title",
            listOf(fixture.addonEvidence(rawLabel = "Chapter 12", externalKey = "same-key")),
        )

        fixture.reconciler.execute(
            "title",
            listOf(
                fixture.addonEvidence(
                    id = "changed-observation",
                    rawLabel = "Chapter 13",
                    externalKey = "same-key",
                ),
            ),
        )

        val chapter = fixture.chapterRepository.getByCanonicalTitleId("title").single()
        chapter.confirmation shouldBe CanonicalChapterConfirmation.CONFLICTED
    }

    // Post-smoke P1 regression: a provider release must never cross canonical chapter identity.
    @Test
    fun `conflicting stable external key is detached from the old canonical chapter`() = runTest {
        val fixture = fixture()

        fixture.reconciler.execute(
            "title",
            listOf(fixture.addonEvidence(rawLabel = "Chapter 4", externalKey = "stable-key")),
        )
        val chapter4 = fixture.chapterRepository.getByCanonicalTitleId("title").single()

        fixture.reconciler.execute(
            "title",
            listOf(
                fixture.addonEvidence(
                    id = "changed",
                    rawLabel = "Chapter 126",
                    externalKey = "stable-key",
                ),
            ),
        )

        fixture.chapterRepository.getById(chapter4.id)?.confirmation shouldBe CanonicalChapterConfirmation.CONFLICTED
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "stable-key",
        )?.mappedCanonicalChapterId shouldBe null
    }

    @Test
    fun `provider omission never deletes an already materialized canonical chapter`() = runTest {
        val fixture = fixture()

        fixture.reconciler.execute(
            "title",
            listOf(fixture.editorialEvidence(rawLabel = "Chapter 50", externalKey = "mal-50")),
        )
        val chapterId = fixture.chapterRepository.getByCanonicalTitleId("title").single().id

        fixture.reconciler.execute("title", emptyList())

        val remaining = fixture.chapterRepository.getByCanonicalTitleId("title").single()
        remaining.id shouldBe chapterId
        remaining.confirmation shouldBe CanonicalChapterConfirmation.CONFIRMED
    }

    private fun fixture(): Fixture {
        val chapterRepository = FakeCanonicalChapterRepository()
        val evidenceRepository = FakeChapterEvidenceRepository()
        var nextId = 0
        val reconciler = ReconcileChapterEvidence(
            parser = ParseCanonicalChapterLabel(),
            canonicalChapterRepository = chapterRepository,
            evidenceRepository = evidenceRepository,
            idFactory = { "chapter-${++nextId}" },
            clock = { 100L },
        )
        return Fixture(reconciler, chapterRepository, evidenceRepository)
    }

    private data class Fixture(
        val reconciler: ReconcileChapterEvidence,
        val chapterRepository: FakeCanonicalChapterRepository,
        val evidenceRepository: FakeChapterEvidenceRepository,
    ) {
        fun addonEvidence(
            id: String = "addon-evidence",
            rawLabel: String,
            externalKey: String?,
        ) = ChapterEvidence(
            id = id,
            canonicalTitleId = "title",
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = externalKey,
            rawLabel = rawLabel,
            rawNumber = null,
            volume = null,
            title = null,
            observedAt = 10L,
            confidence = 1.0,
            authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
        )

        fun editorialEvidence(
            id: String = "editorial-evidence",
            rawLabel: String,
            externalKey: String?,
        ) = ChapterEvidence(
            id = id,
            canonicalTitleId = "title",
            producerKind = ProducerKind.INTEGRATION,
            producerId = "mal",
            externalChapterKey = externalKey,
            rawLabel = rawLabel,
            rawNumber = null,
            volume = null,
            title = null,
            observedAt = 20L,
            confidence = 1.0,
            authority = ChapterEvidenceAuthority.EDITORIAL,
        )
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
            val externalIndex = evidence.externalChapterKey?.let { key ->
                records.indexOfFirst {
                    it.evidence.producerKind == evidence.producerKind &&
                        it.evidence.producerId == evidence.producerId &&
                        it.evidence.externalChapterKey == key
                }
            } ?: -1
            val idIndex = records.indexOfFirst { it.evidence.id == evidence.id }
            val existingIndex = if (externalIndex >= 0) externalIndex else idIndex
            val stableEvidence = if (existingIndex >= 0) {
                evidence.copy(id = records[existingIndex].evidence.id)
            } else {
                evidence
            }
            val persisted = PersistedChapterEvidence(stableEvidence, mappedCanonicalChapterId)
            if (existingIndex >= 0) {
                records[existingIndex] = persisted
            } else {
                records += persisted
            }
            return persisted
        }
    }

    private class FakeCanonicalChapterRepository : CanonicalChapterRepository {
        private val chapters = linkedMapOf<String, CanonicalChapter>()
        private val state = MutableStateFlow<List<CanonicalChapter>>(emptyList())

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> = state

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
            state.value = chapters.values.toList()
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
