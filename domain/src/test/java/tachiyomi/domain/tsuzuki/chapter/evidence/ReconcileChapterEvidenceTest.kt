package tachiyomi.domain.tsuzuki.chapter.evidence

import io.kotest.assertions.throwables.shouldThrow
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
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterVolume
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
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
            control.reconciler.execute(
                "title",
                (138..234).map { number ->
                    control.addonEvidence(
                        id = "observation-$number",
                        rawLabel = "Chapter $number",
                        externalKey = "chapter-$number",
                    )
                },
            )
            observed.chapterRepository.getByCanonicalTitleId("title") shouldHaveSize 97
            observed.reconciler.execute("title", complete)
            control.reconciler.execute(
                "title",
                (1..234).map { number ->
                    control.addonEvidence(
                        id = "observation-$number",
                        rawLabel = "Chapter $number",
                        externalKey = "chapter-$number",
                    )
                },
            )

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
    fun `reconciliation uses the title evidence snapshot instead of querying every external key`() = runTest {
        val fixture = fixture()
        val observations = (1..100).map { number ->
            fixture.addonEvidence(
                id = "observation-$number",
                rawLabel = "Chapter $number",
                externalKey = "chapter-$number",
            )
        }

        fixture.reconciler.execute("title", observations)

        val externalKeyLookupCount = fixture.evidenceRepository.externalKeyLookupCount
        val titleSnapshotCount = fixture.evidenceRepository.titleSnapshotCount
        val batchWriteCount = fixture.evidenceRepository.batchWriteCount
        val singleWriteCount = fixture.evidenceRepository.singleWriteCount
        titleSnapshotCount shouldBe 1
        externalKeyLookupCount shouldBe 0
        batchWriteCount shouldBe 1
        singleWriteCount shouldBe 0
    }

    @Test
    fun `evidence stays unmapped when historical candidates share an identity`() = runTest {
        val fixture = fixture()
        fixture.chapterRepository.upsert(existingChapter("chapter-first"))
        fixture.chapterRepository.upsert(existingChapter("chapter-second"))

        fixture.reconciler.execute(
            "title",
            listOf(fixture.editorialEvidence(rawLabel = "Chapter 4", externalKey = "chapter-4")),
        )

        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.INTEGRATION,
            producerId = "mal",
            externalChapterKey = "chapter-4",
        )?.mappedCanonicalChapterId shouldBe null
        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe
            listOf("chapter-first", "chapter-second")
    }

    @Test
    fun `duplicate candidates for the observed volume remain unmapped`() = runTest {
        val fixture = fixture()
        fixture.chapterRepository.upsert(existingChapter("chapter-first", volume = 1))
        fixture.chapterRepository.upsert(existingChapter("chapter-second", volume = 1))
        val observation = fixture.addonEvidence(
            rawLabel = "Chapter 4",
            externalKey = "source-chapter-4",
            volume = 1,
        )

        fixture.reconciler.execute("title", listOf(observation))
        fixture.reconciler.execute("title", listOf(observation.copy(id = "refresh")))

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe
            listOf("chapter-first", "chapter-second")
        val storedEvidence = fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "source-chapter-4",
        )
        storedEvidence?.evidence?.id shouldBe observation.id
        storedEvidence?.mappedCanonicalChapterId shouldBe null
        fixture.evidenceRepository.getByCanonicalTitleId("title") shouldHaveSize 1
    }

    @Test
    fun `ambiguous explicit volume prefix does not reuse an unqualified candidate`() = runTest {
        val fixture = fixture()
        fixture.chapterRepository.upsert(existingChapter("chapter-unqualified"))

        fixture.reconciler.execute(
            "title",
            listOf(
                fixture.addonEvidence(
                    rawLabel = "Vol.1 Ch.4 - Vol.2 edition",
                    externalKey = "ambiguous-source-key",
                ),
            ),
        )

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe
            listOf("chapter-unqualified")
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "ambiguous-source-key",
        )?.mappedCanonicalChapterId shouldBe null
    }

    @Test
    fun `stable external mapping remains authoritative when duplicate candidates appear`() = runTest {
        val fixture = fixture()
        val originalObservation = fixture.addonEvidence(
            id = "original",
            rawLabel = "Chapter 4",
            externalKey = "stable-source-key",
            volume = 1,
        )
        fixture.reconciler.execute("title", listOf(originalObservation))
        val originalChapterId = fixture.chapterRepository.getByCanonicalTitleId("title").single().id
        fixture.chapterRepository.upsert(existingChapter("historical-duplicate", volume = 1))

        fixture.reconciler.execute(
            "title",
            listOf(originalObservation.copy(id = "refresh")),
        )

        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "stable-source-key",
        )?.mappedCanonicalChapterId shouldBe originalChapterId
        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe
            listOf(originalChapterId, "historical-duplicate")
    }

    @Test
    fun `same number in different volumes creates separate chapters and preserves evidence`() = runTest {
        val fixture = fixture()
        val volumeOne = fixture.addonEvidence(
            id = "volume-one-observation",
            rawLabel = "Chapter 37",
            externalKey = "source-one-37",
            producerId = "source-one",
            volume = 1,
        )
        val volumeTwo = fixture.addonEvidence(
            id = "volume-two-observation",
            rawLabel = "Chapter 37",
            externalKey = "source-two-37",
            producerId = "source-two",
            volume = 2,
        )

        fixture.reconciler.execute("title", listOf(volumeOne, volumeTwo))

        val chapters = fixture.chapterRepository.getByCanonicalTitleId("title")
        chapters shouldHaveSize 2
        chapters.map { it.volume }.toSet() shouldBe setOf(1, 2)
        chapters.map { it.id }.distinct() shouldHaveSize 2
        val observations = fixture.evidenceRepository.getByCanonicalTitleId("title")
        observations shouldHaveSize 2
        observations.associate { it.evidence.id to it.mappedCanonicalChapterId } shouldBe mapOf(
            "volume-one-observation" to chapters.single { it.volume == 1 }.id,
            "volume-two-observation" to chapters.single { it.volume == 2 }.id,
        )
    }

    @Test
    fun `explicit volume selects the matching existing canonical chapter`() = runTest {
        val fixture = fixture()
        fixture.chapterRepository.upsert(existingChapter("chapter-volume-1", volume = 1))
        fixture.chapterRepository.upsert(existingChapter("chapter-volume-2", volume = 2))

        fixture.reconciler.execute(
            "title",
            listOf(
                fixture.addonEvidence(
                    rawLabel = "Chapter 4",
                    externalKey = "source-volume-2-chapter-4",
                    volume = 2,
                ),
            ),
        )

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe
            listOf("chapter-volume-1", "chapter-volume-2")
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "source-volume-2-chapter-4",
        )?.mappedCanonicalChapterId shouldBe "chapter-volume-2"
    }

    @Test
    fun `same identity and volume across sources reuse one canonical chapter id`() = runTest {
        val fixture = fixture()
        val firstSource = fixture.addonEvidence(
            id = "first-source-observation",
            rawLabel = "Chapter 37",
            externalKey = "first-source-37",
            producerId = "source-one",
            volume = 1,
        )
        val secondSource = fixture.addonEvidence(
            id = "second-source-observation",
            rawLabel = "Chapter 37",
            externalKey = "second-source-37",
            producerId = "source-two",
            volume = 1,
        )

        fixture.reconciler.execute("title", listOf(firstSource, secondSource))

        val chapters = fixture.chapterRepository.getByCanonicalTitleId("title")
        chapters shouldHaveSize 1
        val observations = fixture.evidenceRepository.getByCanonicalTitleId("title")
        observations shouldHaveSize 2
        observations.map { it.mappedCanonicalChapterId }.distinct() shouldBe listOf(chapters.single().id)
    }

    @Test
    fun `stale legacy observation from another producer cannot fork newer mapped chapter evidence`() = runTest {
        val fixture = fixture()
        val detailObservation = fixture.addonEvidence(
            id = "detail-chapter-two",
            rawLabel = "Chapter 2",
            externalKey = "101:/chapter/shared",
            producerId = "detail-addon",
        ).copy(observedAt = 200L)
        fixture.reconciler.execute("title", listOf(detailObservation))
        val chapterTwo = fixture.chapterRepository.getByCanonicalTitleId("title").single()

        val staleLegacyObservation = fixture.addonEvidence(
            id = "legacy-chapter-one",
            rawLabel = "Chapter 1",
            externalKey = "101:/chapter/shared",
            producerId = "mihon-legacy:title:101",
        ).copy(observedAt = 100L)
        fixture.reconciler.execute("title", listOf(staleLegacyObservation))

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe listOf(chapterTwo.id)
        fixture.evidenceRepository.getByCanonicalTitleId("title") shouldHaveSize 1
        fixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "detail-addon",
            "101:/chapter/shared",
        )?.mappedCanonicalChapterId shouldBe chapterTwo.id
        fixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "mihon-legacy:title:101",
            "101:/chapter/shared",
        ) shouldBe null
    }

    @Test
    fun `older legacy observation matching newer detail identity may reuse its canonical mapping`() = runTest {
        val fixture = fixture()
        val detailObservation = fixture.addonEvidence(
            id = "detail-chapter-two",
            rawLabel = "Chapter 2",
            externalKey = "101:/chapter/shared",
            producerId = "detail-addon",
        ).copy(observedAt = 200L)
        fixture.reconciler.execute("title", listOf(detailObservation))
        val chapterTwo = fixture.chapterRepository.getByCanonicalTitleId("title").single()

        val legacyObservation = fixture.addonEvidence(
            id = "legacy-chapter-two",
            rawLabel = "Chapter 2",
            externalKey = "101:/chapter/shared",
            producerId = "mihon-legacy:title:101",
        ).copy(observedAt = 100L)
        fixture.reconciler.execute("title", listOf(legacyObservation))

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe listOf(chapterTwo.id)
        fixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "mihon-legacy:title:101",
            "101:/chapter/shared",
        )?.mappedCanonicalChapterId shouldBe chapterTwo.id
    }

    @Test
    fun `newer detail conflict cannot fork an existing legacy source chapter identity`() = runTest {
        val fixture = fixture()
        val legacyObservation = fixture.addonEvidence(
            id = "legacy-chapter-one",
            rawLabel = "Chapter 1",
            externalKey = "101:/chapter/shared",
            producerId = "mihon-legacy:title:101",
        ).copy(observedAt = 100L)
        fixture.reconciler.execute("title", listOf(legacyObservation))
        val legacyChapter = fixture.chapterRepository.getByCanonicalTitleId("title").single()
        val existingVariant = ChapterVariant(
            id = "legacy-variant",
            canonicalChapterId = legacyChapter.id,
            sourceId = 101L,
            sourceChapterId = "/chapter/shared",
            rawName = "Chapter 1",
        )
        fixture.chapterRepository.upsertVariant(existingVariant)

        val newerDetailObservation = fixture.addonEvidence(
            id = "detail-chapter-two",
            rawLabel = "Chapter 2",
            externalKey = "101:/chapter/shared",
            producerId = "detail-addon",
        ).copy(observedAt = 200L)
        fixture.reconciler.execute("title", listOf(newerDetailObservation))
        fixture.reconciler.execute("title", listOf(newerDetailObservation))

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe listOf(legacyChapter.id)
        fixture.chapterRepository.getById(legacyChapter.id)?.confirmation shouldBe
            CanonicalChapterConfirmation.CONFLICTED
        fixture.chapterRepository.getVariantBySourceIdentity(101L, "/chapter/shared") shouldBe existingVariant
        fixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "mihon-legacy:title:101",
            "101:/chapter/shared",
        )?.mappedCanonicalChapterId shouldBe legacyChapter.id
        fixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "detail-addon",
            "101:/chapter/shared",
        )?.mappedCanonicalChapterId shouldBe null
    }

    @Test
    fun `newer legacy conflict cannot fork an existing detail source chapter identity`() = runTest {
        val fixture = fixture()
        val detailObservation = fixture.addonEvidence(
            id = "detail-chapter-two",
            rawLabel = "Chapter 2",
            externalKey = "101:/chapter/shared",
            producerId = "detail-addon",
        ).copy(observedAt = 100L)
        fixture.reconciler.execute("title", listOf(detailObservation))
        val detailChapter = fixture.chapterRepository.getByCanonicalTitleId("title").single()
        val existingVariant = ChapterVariant(
            id = "detail-variant",
            canonicalChapterId = detailChapter.id,
            sourceId = 101L,
            sourceChapterId = "/chapter/shared",
            rawName = "Chapter 2",
        )
        fixture.chapterRepository.upsertVariant(existingVariant)

        val newerLegacyObservation = fixture.addonEvidence(
            id = "legacy-chapter-one",
            rawLabel = "Chapter 1",
            externalKey = "101:/chapter/shared",
            producerId = "mihon-legacy:title:101",
        ).copy(observedAt = 200L)
        fixture.reconciler.execute("title", listOf(newerLegacyObservation))
        fixture.reconciler.execute("title", listOf(newerLegacyObservation))

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe listOf(detailChapter.id)
        fixture.chapterRepository.getVariantBySourceIdentity(101L, "/chapter/shared") shouldBe existingVariant
        fixture.chapterRepository.getVariantsByCanonicalChapterId(detailChapter.id) shouldBe listOf(existingVariant)
        fixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "detail-addon",
            "101:/chapter/shared",
        )?.mappedCanonicalChapterId shouldBe detailChapter.id
        fixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "mihon-legacy:title:101",
            "101:/chapter/shared",
        )?.mappedCanonicalChapterId shouldBe null
    }

    @Test
    fun `conflicting addon observations with the same fetch start fail closed`() = runTest {
        val fixture = fixture()
        val firstObservation = fixture.addonEvidence(
            id = "detail-chapter-two",
            rawLabel = "Chapter 2",
            externalKey = "101:/chapter/shared",
            producerId = "detail-addon",
        ).copy(observedAt = 200L)
        fixture.reconciler.execute("title", listOf(firstObservation))
        val chapterTwo = fixture.chapterRepository.getByCanonicalTitleId("title").single()

        val contradictoryReplay = fixture.addonEvidence(
            id = "detail-chapter-one",
            rawLabel = "Chapter 1",
            externalKey = "101:/chapter/shared",
            producerId = "detail-addon",
        ).copy(observedAt = 200L)
        shouldThrow<IllegalArgumentException> {
            fixture.reconciler.execute("title", listOf(contradictoryReplay))
        }

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe listOf(chapterTwo.id)
        val persisted = fixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "detail-addon",
            "101:/chapter/shared",
        )
        persisted?.evidence?.rawLabel shouldBe "Chapter 2"
        persisted?.mappedCanonicalChapterId shouldBe chapterTwo.id
    }

    @Test
    fun `identical addon observation replay at the same fetch start remains idempotent`() = runTest {
        val fixture = fixture()
        val original = fixture.addonEvidence(
            id = "detail-observation-original",
            rawLabel = "Chapter 2",
            externalKey = "101:/chapter/shared",
            producerId = "detail-addon",
        ).copy(observedAt = 200L)
        fixture.reconciler.execute("title", listOf(original))
        val chapterTwo = fixture.chapterRepository.getByCanonicalTitleId("title").single()

        fixture.reconciler.execute("title", listOf(original.copy(id = "detail-observation-replay")))

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe listOf(chapterTwo.id)
        val persisted = fixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "detail-addon",
            "101:/chapter/shared",
        )
        persisted?.evidence shouldBe original
        persisted?.mappedCanonicalChapterId shouldBe chapterTwo.id
    }

    @Test
    fun `editorial evidence may change at the same caller timestamp`() = runTest {
        val fixture = fixture()
        val firstObservation = fixture.editorialEvidence(
            rawLabel = "Chapter 1",
            externalKey = "editorial-chapter-shared",
        )
        fixture.reconciler.execute("title", listOf(firstObservation))

        val sameTimestampUpdate = fixture.editorialEvidence(
            rawLabel = "Chapter 2",
            externalKey = "editorial-chapter-shared",
        ).copy(observedAt = firstObservation.observedAt)
        fixture.reconciler.execute("title", listOf(sameTimestampUpdate))

        fixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.INTEGRATION,
            "mal",
            "editorial-chapter-shared",
        )?.evidence?.rawLabel shouldBe "Chapter 2"
    }

    @Test
    fun `legacy evidence adapter rehomes a reused URL without losing an independently supported chapter`() =
        runTest {
            val fixture = fixture()
            val volumeOne = existingChapter("chapter-volume-1", volume = 1)
            val volumeTwo = existingChapter("chapter-volume-2", volume = 2)
            fixture.chapterRepository.upsert(volumeOne)
            fixture.chapterRepository.upsert(volumeTwo)
            val adapter = LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume())

            fun sourceInventory(sourceId: Long, label: String) = SourceChapterInventory(
                sourceMappingId = "mapping-" + sourceId,
                sourceId = sourceId,
                canonicalTitleId = "title",
                language = if (sourceId == 101L) "en" else "pt-BR",
                chapters = listOf(
                    SourceChapterSnapshot(
                        sourceId = sourceId,
                        sourceMappingId = "mapping-" + sourceId,
                        sourceChapterId = "/reused-key",
                        rawName = label,
                        rawNumberHint = 4.0,
                    ),
                ),
            )
            val en = sourceInventory(101L, "Vol. 1 Ch. 4")
            val pt = sourceInventory(202L, "Vol. 1 Ch. 4")
            fixture.reconciler.execute(
                "title",
                adapter.adapt(en, 10L) + adapter.adapt(pt, 10L),
            )

            suspend fun mappedChapter(sourceId: Long): String? =
                fixture.evidenceRepository.getByProducerExternalKey(
                    ProducerKind.ADDON,
                    "mihon-legacy:title:" + sourceId,
                    "$sourceId:/reused-key",
                )?.mappedCanonicalChapterId
            mappedChapter(101L) shouldBe volumeOne.id
            mappedChapter(202L) shouldBe volumeOne.id

            // A reused URL is not proof that the new edition is still volume 1.
            fixture.reconciler.execute(
                "title",
                adapter.adapt(sourceInventory(101L, "Vol. 2 Ch. 4"), 20L),
            )
            mappedChapter(101L) shouldBe volumeTwo.id
            mappedChapter(202L) shouldBe volumeOne.id
            fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id }.toSet() shouldBe
                setOf(volumeOne.id, volumeTwo.id)

            // A later partial inventory never discards evidence for an omitted source.
            fixture.reconciler.execute("title", adapter.adapt(en.copy(chapters = emptyList()), 30L))
            mappedChapter(101L) shouldBe volumeTwo.id
            mappedChapter(202L) shouldBe volumeOne.id
        }

    @Test
    fun `changed volume on stable key conflicts old chapter and rehomes evidence`() = runTest {
        val fixture = fixture()
        fixture.reconciler.execute(
            "title",
            listOf(
                fixture.addonEvidence(
                    id = "initial-observation",
                    rawLabel = "Chapter 37",
                    externalKey = "reused-source-key",
                    volume = 1,
                ),
            ),
        )
        val originalChapter = fixture.chapterRepository.getByCanonicalTitleId("title").single()

        fixture.reconciler.execute(
            "title",
            listOf(
                fixture.addonEvidence(
                    id = "updated-observation",
                    rawLabel = "Chapter 37",
                    externalKey = "reused-source-key",
                    volume = 2,
                ).copy(observedAt = 20L),
            ),
        )

        fixture.chapterRepository.getById(originalChapter.id)?.confirmation shouldBe
            CanonicalChapterConfirmation.CONFLICTED
        val volumeTwoChapter = fixture.chapterRepository.getByCanonicalTitleId("title")
            .single { it.volume == 2 }
        volumeTwoChapter.id shouldBe "chapter-2"
        val storedEvidence = fixture.evidenceRepository.getByCanonicalTitleId("title").single()
        storedEvidence.evidence.id shouldBe "initial-observation"
        storedEvidence.mappedCanonicalChapterId shouldBe volumeTwoChapter.id
    }

    @Test
    fun `reliable unqualified add-on observation reuses sole explicit volume candidate`() = runTest {
        val fixture = fixture()
        val volumeOne = fixture.addonEvidence(
            id = "volume-one-observation",
            rawLabel = "Chapter 37",
            externalKey = "source-one-37",
            producerId = "source-one",
            volume = 1,
        )
        val unknownVolume = fixture.addonEvidence(
            id = "unknown-volume-observation",
            rawLabel = "Chapter 37",
            externalKey = "source-two-37",
            producerId = "source-two",
        )

        fixture.reconciler.execute("title", listOf(volumeOne, unknownVolume))

        val chapters = fixture.chapterRepository.getByCanonicalTitleId("title")
        chapters shouldHaveSize 1
        val canonical = chapters.single()
        canonical.volume shouldBe 1
        canonical.baseNumber shouldBe 37
        fixture.evidenceRepository.getByCanonicalTitleId("title") shouldHaveSize 2
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "source-one",
            externalChapterKey = "source-one-37",
        )?.mappedCanonicalChapterId shouldBe canonical.id
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "source-two",
            externalChapterKey = "source-two-37",
        )?.mappedCanonicalChapterId shouldBe canonical.id
    }

    @Test
    fun `unqualified add-on evidence stays unmapped when multiple unqualified candidates exist`() = runTest {
        val fixture = fixture()
        fixture.chapterRepository.upsert(existingChapter("chapter-first"))
        fixture.chapterRepository.upsert(existingChapter("chapter-second"))

        fixture.reconciler.execute(
            "title",
            listOf(fixture.addonEvidence(rawLabel = "Chapter 4", externalKey = "ambiguous-chapter-4")),
        )

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe
            listOf("chapter-first", "chapter-second")
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "ambiguous-chapter-4",
        )?.mappedCanonicalChapterId shouldBe null
    }

    @Test
    fun `editorial unqualified evidence stays unmapped beside an explicit volume candidate`() = runTest {
        val fixture = fixture()
        fixture.chapterRepository.upsert(existingChapter("chapter-volume-1", volume = 1))

        fixture.reconciler.execute(
            "title",
            listOf(fixture.editorialEvidence(rawLabel = "Chapter 4", externalKey = "editorial-chapter-4")),
        )

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe listOf("chapter-volume-1")
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.INTEGRATION,
            producerId = "mal",
            externalChapterKey = "editorial-chapter-4",
        )?.mappedCanonicalChapterId shouldBe null
    }

    @Test
    fun `editorial unqualified evidence remains ambiguous beside qualified and unqualified candidates`() = runTest {
        val fixture = fixture()
        fixture.chapterRepository.upsert(existingChapter("chapter-unqualified"))
        fixture.chapterRepository.upsert(existingChapter("chapter-volume-1", volume = 1))

        fixture.reconciler.execute(
            "title",
            listOf(fixture.editorialEvidence(rawLabel = "Chapter 4", externalKey = "editorial-chapter-4")),
        )

        fixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe
            listOf("chapter-unqualified", "chapter-volume-1")
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.INTEGRATION,
            producerId = "mal",
            externalChapterKey = "editorial-chapter-4",
        )?.mappedCanonicalChapterId shouldBe null
    }

    @Test
    fun `unknown volume refresh preserves a previously mapped stable external key`() = runTest {
        val fixture = fixture()
        val volumeOne = fixture.addonEvidence(
            id = "first-source-observation",
            rawLabel = "Chapter 37",
            externalKey = "first-source-37",
            producerId = "source-one",
            volume = 1,
        )
        val volumeTwo = fixture.addonEvidence(
            id = "second-source-observation",
            rawLabel = "Chapter 37",
            externalKey = "second-source-37",
            producerId = "source-two",
            volume = 2,
        )
        fixture.reconciler.execute("title", listOf(volumeOne, volumeTwo))
        val volumeOneChapterId = fixture.chapterRepository.getByCanonicalTitleId("title")
            .single { it.volume == 1 }
            .id

        fixture.reconciler.execute(
            "title",
            listOf(volumeOne.copy(id = "first-source-refresh", volume = null, observedAt = 20L)),
        )

        fixture.chapterRepository.getByCanonicalTitleId("title") shouldHaveSize 2
        val storedEvidence = fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "source-one",
            externalChapterKey = "first-source-37",
        )
        storedEvidence?.evidence?.id shouldBe "first-source-observation"
        storedEvidence?.mappedCanonicalChapterId shouldBe volumeOneChapterId
    }

    @Test
    fun `unknown volume stays unmapped when the same chapter identity has multiple volume candidates`() = runTest {
        val fixture = fixture()
        val knownVolumes = listOf(
            fixture.addonEvidence(
                id = "volume-one-observation",
                rawLabel = "Chapter 37",
                externalKey = "source-one-37",
                producerId = "source-one",
                volume = 1,
            ),
            fixture.addonEvidence(
                id = "volume-two-observation",
                rawLabel = "Chapter 37",
                externalKey = "source-two-37",
                producerId = "source-two",
                volume = 2,
            ),
        )
        val unknownVolume = fixture.addonEvidence(
            id = "unknown-volume-observation",
            rawLabel = "Chapter 37",
            externalKey = "source-three-37",
            producerId = "source-three",
        )

        fixture.reconciler.execute("title", knownVolumes + unknownVolume)

        fixture.chapterRepository.getByCanonicalTitleId("title") shouldHaveSize 2
        fixture.evidenceRepository.getByCanonicalTitleId("title") shouldHaveSize 3
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "source-three",
            externalChapterKey = "source-three-37",
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
                ).copy(observedAt = 20L),
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
                ).copy(observedAt = 20L),
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
                ).copy(observedAt = 20L),
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
                fixture.addonEvidence(id = "en-new", rawLabel = "Chapter 126", externalKey = "en-stable")
                    .copy(observedAt = 20L),
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

    private fun existingChapter(id: String, volume: Int? = null) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title",
        displayNumber = "4",
        volume = volume,
        title = null,
        type = tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType.REGULAR,
        baseNumber = 4,
        part = null,
        alphaSuffix = null,
        confidence = 1.0,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private data class Fixture(
        val reconciler: ReconcileChapterEvidence,
        val chapterRepository: FakeCanonicalChapterRepository,
        val evidenceRepository: FakeChapterEvidenceRepository,
    ) {
        fun addonEvidence(
            id: String = "addon-evidence",
            rawLabel: String,
            externalKey: String?,
            producerId: String = "addon",
            volume: Int? = null,
        ) = ChapterEvidence(
            id = id,
            canonicalTitleId = "title",
            producerKind = ProducerKind.ADDON,
            producerId = producerId,
            externalChapterKey = externalKey,
            rawLabel = rawLabel,
            rawNumber = null,
            volume = volume,
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
        var titleSnapshotCount = 0
            private set
        var externalKeyLookupCount = 0
            private set
        var batchWriteCount = 0
            private set
        var singleWriteCount = 0
            private set

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<PersistedChapterEvidence> {
            titleSnapshotCount++
            return records.filter { it.evidence.canonicalTitleId == canonicalTitleId }
        }

        override suspend fun getByProducerExternalKey(
            producerKind: ProducerKind,
            producerId: String,
            externalChapterKey: String,
        ): PersistedChapterEvidence? {
            externalKeyLookupCount++
            return records.firstOrNull {
                it.evidence.producerKind == producerKind &&
                    it.evidence.producerId == producerId &&
                    it.evidence.externalChapterKey == externalChapterKey
            }
        }

        override suspend fun upsert(
            evidence: ChapterEvidence,
            mappedCanonicalChapterId: String?,
        ): PersistedChapterEvidence {
            singleWriteCount++
            return persist(evidence, mappedCanonicalChapterId)
        }

        override suspend fun upsertBatch(
            writes: List<tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceWrite>,
        ): List<PersistedChapterEvidence> {
            batchWriteCount++
            return writes.map { write -> persist(write.evidence, write.mappedCanonicalChapterId) }
        }

        private fun persist(
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
        private val variants = linkedMapOf<Pair<Long, String>, ChapterVariant>()
        private val state = MutableStateFlow<List<CanonicalChapter>>(emptyList())

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> = state

        override suspend fun getById(id: String): CanonicalChapter? = chapters[id]

        override suspend fun getVariantBySourceIdentity(
            sourceId: Long,
            sourceChapterId: String,
        ): ChapterVariant? = variants[sourceId to sourceChapterId]

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            variants.values.filter { it.canonicalChapterId == canonicalChapterId }

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            variants.values.filter { it.sourceMappingId == sourceMappingId }

        override suspend fun upsert(chapter: CanonicalChapter) {
            chapters[chapter.id] = chapter
            state.value = chapters.values.toList()
        }

        override suspend fun upsertVariant(variant: ChapterVariant) {
            variants[variant.sourceId to variant.sourceChapterId] = variant
        }

        override suspend fun upsertBatch(
            chapters: List<CanonicalChapter>,
            variants: List<ChapterVariant>,
        ) {
            chapters.forEach { upsert(it) }
            variants.forEach { upsertVariant(it) }
        }
    }
}
