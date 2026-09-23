package tachiyomi.domain.tsuzuki.chapter.evidence

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
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
    fun `diagnostic records low confidence evidence as provisional without changing reconciliation`() = runTest {
        val diagnostics = RecordingDiagnostics().apply { start("title") }
        val fixture = fixture(diagnostics)
        val observation = fixture.addonEvidence(
            rawLabel = "Chapter 9.46",
            externalKey = "suspicious-9-46",
        )

        fixture.reconciler.execute("title", listOf(observation))

        fixture.chapterRepository.getByCanonicalTitleId("title") shouldHaveSize 0
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "suspicious-9-46",
        )?.mappedCanonicalChapterId shouldBe null
        val event = diagnostics.events.single { it.stage == ChapterInventoryDiagnosticStage.RECONCILIATION }
        event.outcome shouldBe ChapterInventoryDiagnosticOutcome.LOW_CONFIDENCE
        event.received shouldBe 1
        event.accepted shouldBe 0
        event.provisional shouldBe 1
        event.discarded shouldBe 0
        event.reasons[ChapterInventoryDiagnosticReason.LOW_CONFIDENCE] shouldBe 1
    }

    @Test
    fun `partial persisted sequence followed by complete inventory reports first boundary and preserves final graph`() =
        runTest {
            val diagnostics = RecordingDiagnostics().apply { start("title") }
            val observed = fixture(diagnostics)
            val control = fixture()
            val partial = (138..234).map { number ->
                observed.addonEvidence(
                    id = "observation-$number",
                    rawLabel = "Chapter $number",
                    externalKey = "chapter-$number",
                )
            }
            val complete = (1..234).map { number ->
                observed.addonEvidence(
                    id = "observation-$number",
                    rawLabel = "Chapter $number",
                    externalKey = "chapter-$number",
                )
            }

            observed.reconciler.execute("title", partial)
            control.reconciler.execute("title", (138..234).map { number ->
                control.addonEvidence(
                    id = "observation-$number",
                    rawLabel = "Chapter $number",
                    externalKey = "chapter-$number",
                )
            })
            observed.chapterRepository.getByCanonicalTitleId("title") shouldHaveSize 97
            observed.reconciler.execute("title", complete)
            control.reconciler.execute("title", (1..234).map { number ->
                control.addonEvidence(
                    id = "observation-$number",
                    rawLabel = "Chapter $number",
                    externalKey = "chapter-$number",
                )
            })

            val actual = observed.chapterRepository.getByCanonicalTitleId("title")
            val expected = control.chapterRepository.getByCanonicalTitleId("title")
            actual shouldHaveSize 234
            actual.map { it.identity to it.displayNumber }.toSet() shouldBe
                expected.map { it.identity to it.displayNumber }.toSet()
            val reconciliationEvents = diagnostics.events.filter {
                it.stage == ChapterInventoryDiagnosticStage.RECONCILIATION
            }
            reconciliationEvents.map { it.received } shouldBe listOf(97, 234)
            reconciliationEvents.first().labels.first() shouldBe "138"
            reconciliationEvents.last().labels.first() shouldBe "1"
            val persistenceEvents = diagnostics.events.filter {
                it.stage == ChapterInventoryDiagnosticStage.PERSISTENCE
            }
            persistenceEvents.last().accepted shouldBe 234
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

    // Regression guard: a provider reusing a stable key must never cross canonical chapter identity.
    @Test
    fun `reused external key conflicts old chapter and rehomes evidence`() = runTest {
        val fixture = fixture()

        fixture.reconciler.execute(
            "title",
            listOf(fixture.addonEvidence(rawLabel = "Chapter 12", externalKey = "same-key")),
        )
        val chapter12 = fixture.chapterRepository.getByCanonicalTitleId("title").single()

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

        fixture.chapterRepository.getById(chapter12.id)?.confirmation shouldBe
            CanonicalChapterConfirmation.CONFLICTED
        val chapter13 = fixture.chapterRepository.getByCanonicalTitleId("title")
            .single { it.baseNumber == 13 }
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "same-key",
        )?.mappedCanonicalChapterId shouldBe chapter13.id
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
        val chapter126 = fixture.chapterRepository.getByCanonicalTitleId("title")
            .single { it.baseNumber == 126 }
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "stable-key",
        )?.mappedCanonicalChapterId shouldBe chapter126.id
    }

    @Test
    fun `one conflicting provider release does not poison independently supported canonical chapter`() = runTest {
        val fixture = fixture()

        fixture.reconciler.execute(
            "title",
            listOf(
                fixture.addonEvidence(id = "pt", rawLabel = "Chapter 4", externalKey = "pt-4"),
                fixture.addonEvidence(id = "en", rawLabel = "Chapter 4", externalKey = "en-stable"),
            ),
        )
        val chapter4 = fixture.chapterRepository.getByCanonicalTitleId("title").single()

        fixture.reconciler.execute(
            "title",
            listOf(
                fixture.addonEvidence(id = "en-new", rawLabel = "Chapter 126", externalKey = "en-stable"),
            ),
        )

        fixture.chapterRepository.getById(chapter4.id)?.confirmation shouldBe
            CanonicalChapterConfirmation.PROVISIONAL
        val chapter126 = fixture.chapterRepository.getByCanonicalTitleId("title")
            .single { it.baseNumber == 126 }
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "en-stable",
        )?.mappedCanonicalChapterId shouldBe chapter126.id
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "pt-4",
        )?.mappedCanonicalChapterId shouldBe chapter4.id
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

    private fun fixture(diagnostics: ChapterInventoryDiagnostics? = null): Fixture {
        val chapterRepository = FakeCanonicalChapterRepository()
        val evidenceRepository = FakeChapterEvidenceRepository()
        var nextId = 0
        val reconciler = ReconcileChapterEvidence(
            parser = ParseCanonicalChapterLabel(),
            canonicalChapterRepository = chapterRepository,
            evidenceRepository = evidenceRepository,
            idFactory = { "chapter-${++nextId}" },
            clock = { 100L },
            diagnostics = diagnostics ?: NoOpChapterInventoryDiagnostics,
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

    private class RecordingDiagnostics : ChapterInventoryDiagnostics {
        val events = mutableListOf<ChapterInventoryDiagnosticEvent>()
        private var recordingTitle: String? = null

        override fun start(canonicalTitleId: String): String {
            recordingTitle = canonicalTitleId
            return "test-session"
        }

        override fun stop() {
            recordingTitle = null
        }

        override fun clear() {
            events.clear()
            recordingTitle = null
        }

        override fun isRecording(canonicalTitleId: String): Boolean = recordingTitle == canonicalTitleId

        override fun record(event: ChapterInventoryDiagnosticEvent) {
            if (recordingTitle != null) events += event
        }

        override fun report(): String = events.joinToString("\n")
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
