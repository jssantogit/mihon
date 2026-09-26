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
import tachiyomi.domain.tsuzuki.chapter.interactor.ReconcileChapterInventory
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
    fun `legacy and evidence writers reuse the same volume scoped chapters and source mappings`() = runTest {
        val legacyChapters = FakeCanonicalChapterRepository()
        val evidenceFixture = fixture()
        for (volume in 1..2) {
            val chapter = existingChapter("chapter-volume-$volume", volume)
            legacyChapters.upsert(chapter)
            evidenceFixture.chapterRepository.upsert(chapter)
        }

        var nextVariant = 0
        val legacy = ReconcileChapterInventory(
            parser = ParseCanonicalChapterLabel(),
            volumeParser = ParseCanonicalChapterVolume(),
            canonicalChapterRepository = legacyChapters,
            idFactory = { error("Explicit volume must match one of the seeded chapters") },
            variantIdFactory = { "variant-${++nextVariant}" },
            clock = { 100L },
        )
        data class Observation(
            val sourceId: Long,
            val sourceChapterId: String,
            val rawName: String,
            val volume: Int,
        )
        val observations = listOf(
            Observation(101L, "/volume/1/chapter/4", "Vol. 1 Ch. 4", 1),
            Observation(101L, "/volume/2/chapter/4", "Vol. 2 Ch. 4", 2),
            Observation(202L, "/volume/1/chapter/4", "Vol. 1 Ch. 4", 1),
        )

        suspend fun refresh() {
            val inventories = observations.groupBy(Observation::sourceId).map { (sourceId, rows) ->
                SourceChapterInventory(
                    sourceMappingId = "mapping-$sourceId",
                    sourceId = sourceId,
                    canonicalTitleId = "title",
                    chapters = rows.map { row ->
                        SourceChapterSnapshot(
                            sourceId = sourceId,
                            sourceMappingId = "mapping-$sourceId",
                            sourceChapterId = row.sourceChapterId,
                            rawName = row.rawName,
                            rawNumberHint = 4.0,
                            mihonMangaId = sourceId,
                        )
                    },
                )
            }
            legacy.execute(inventories)
            evidenceFixture.reconciler.execute(
                "title",
                observations.mapIndexed { index, row ->
                    evidenceFixture.addonEvidence(
                        id = "observation-$index",
                        rawLabel = row.rawName,
                        externalKey = "${row.sourceId}:${row.sourceChapterId}",
                        producerId = "addon-${row.sourceId}",
                        volume = row.volume,
                    )
                },
            )
        }

        refresh()
        val expected = setOf("chapter-volume-1", "chapter-volume-2")
        legacyChapters.getByCanonicalTitleId("title").map { it.id }.toSet() shouldBe expected
        evidenceFixture.chapterRepository.getByCanonicalTitleId("title").map { it.id }.toSet() shouldBe expected
        for (row in observations) {
            val canonicalId = "chapter-volume-${row.volume}"
            legacyChapters.getVariantBySourceIdentity(row.sourceId, row.sourceChapterId)
                ?.canonicalChapterId shouldBe canonicalId
            evidenceFixture.evidenceRepository.getByProducerExternalKey(
                ProducerKind.ADDON,
                "addon-${row.sourceId}",
                "${row.sourceId}:${row.sourceChapterId}",
            )?.mappedCanonicalChapterId shouldBe canonicalId
        }
        val firstVariantIds = observations.map { row ->
            legacyChapters.getVariantBySourceIdentity(row.sourceId, row.sourceChapterId)?.id
        }

        refresh()
        legacyChapters.getByCanonicalTitleId("title").map { it.id }.toSet() shouldBe expected
        evidenceFixture.chapterRepository.getByCanonicalTitleId("title").map { it.id }.toSet() shouldBe expected
        observations.map { row ->
            legacyChapters.getVariantBySourceIdentity(row.sourceId, row.sourceChapterId)?.id
        } shouldBe firstVariantIds
    }

    @Test
    fun `both writers retain fractional chapters when a later source inventory is partial`() = runTest {
        val legacyChapters = FakeCanonicalChapterRepository()
        val evidenceFixture = fixture()
        var nextChapter = 0
        var nextVariant = 0
        val legacy = ReconcileChapterInventory(
            parser = ParseCanonicalChapterLabel(),
            volumeParser = ParseCanonicalChapterVolume(),
            canonicalChapterRepository = legacyChapters,
            idFactory = { "legacy-${++nextChapter}" },
            variantIdFactory = { "variant-${++nextVariant}" },
            clock = { 100L },
        )
        val rows = listOf(
            Triple("/chapter/0", "Chapter 0", 0.0),
            Triple("/chapter/0.5", "Chapter 0.5", 0.5),
            Triple("/chapter/1", "Chapter 1", 1.0),
        )
        suspend fun refresh(selected: List<Triple<String, String, Double>>) {
            legacy.execute(
                SourceChapterInventory(
                    sourceMappingId = "mapping-en",
                    sourceId = 101L,
                    canonicalTitleId = "title",
                    chapters = selected.map { (url, label, number) ->
                        SourceChapterSnapshot(
                            sourceId = 101L,
                            sourceMappingId = "mapping-en",
                            sourceChapterId = url,
                            rawName = label,
                            rawNumberHint = number,
                        )
                    },
                ),
            )
            evidenceFixture.reconciler.execute(
                "title",
                selected.map { (url, label, _) ->
                    evidenceFixture.addonEvidence(
                        id = "observation-$url",
                        rawLabel = label,
                        externalKey = "101:$url",
                        producerId = "addon-101",
                    )
                },
            )
        }

        refresh(rows)
        val expectedLabels = setOf("0", "0.5", "1")
        legacyChapters.getByCanonicalTitleId("title").map { it.displayNumber }.toSet() shouldBe expectedLabels
        evidenceFixture.chapterRepository.getByCanonicalTitleId("title")
            .map { it.displayNumber }.toSet() shouldBe expectedLabels
        val previousVariants = rows.associate { (url, _, _) ->
            url to requireNotNull(legacyChapters.getVariantBySourceIdentity(101L, url)).id
        }
        val previousEvidence = rows.associate { (url, _, _) ->
            url to requireNotNull(
                evidenceFixture.evidenceRepository.getByProducerExternalKey(
                    ProducerKind.ADDON,
                    "addon-101",
                    "101:$url",
                )?.mappedCanonicalChapterId,
            )
        }
        rows.forEach { (url, _, _) ->
            val legacyMapped = requireNotNull(
                legacyChapters.getById(
                    requireNotNull(legacyChapters.getVariantBySourceIdentity(101L, url)).canonicalChapterId,
                ),
            )
            val evidenceMapped = requireNotNull(
                evidenceFixture.chapterRepository.getById(requireNotNull(previousEvidence[url])),
            )
            legacyMapped.identity shouldBe evidenceMapped.identity
        }

        // The provider omits chapter 0.5 on its next refresh. Neither writer
        // may erase its established canonical identity or its source mapping.
        refresh(listOf(rows.first(), rows.last()))
        legacyChapters.getByCanonicalTitleId("title").map { it.displayNumber }.toSet() shouldBe expectedLabels
        evidenceFixture.chapterRepository.getByCanonicalTitleId("title")
            .map { it.displayNumber }.toSet() shouldBe expectedLabels
        rows.forEach { (url, _, _) ->
            legacyChapters.getVariantBySourceIdentity(101L, url)?.id shouldBe previousVariants[url]
            evidenceFixture.evidenceRepository.getByProducerExternalKey(
                ProducerKind.ADDON,
                "addon-101",
                "101:$url",
            )?.mappedCanonicalChapterId shouldBe previousEvidence[url]
        }
    }

    @Test
    fun `both writers preserve mapped chapters after empty inventory from one or all sources`() = runTest {
        val legacyChapters = FakeCanonicalChapterRepository()
        val evidenceFixture = fixture()
        val chapter = existingChapter("chapter-volume-1", volume = 1)
        legacyChapters.upsert(chapter)
        evidenceFixture.chapterRepository.upsert(chapter)
        var nextVariant = 0
        val legacy = ReconcileChapterInventory(
            parser = ParseCanonicalChapterLabel(),
            volumeParser = ParseCanonicalChapterVolume(),
            canonicalChapterRepository = legacyChapters,
            idFactory = { error("An empty refresh must not materialize new chapters") },
            variantIdFactory = { "variant-${++nextVariant}" },
            clock = { 100L },
        )
        fun inventory(sourceId: Long, chaptersPresent: Boolean) = SourceChapterInventory(
            sourceMappingId = "mapping-$sourceId",
            sourceId = sourceId,
            canonicalTitleId = "title",
            chapters = if (chaptersPresent) {
                listOf(
                    SourceChapterSnapshot(
                        sourceId = sourceId,
                        sourceMappingId = "mapping-$sourceId",
                        sourceChapterId = "/volume/1/chapter/4",
                        rawName = "Vol. 1 Ch. 4",
                        rawNumberHint = 4.0,
                    ),
                )
            } else {
                emptyList()
            },
        )
        val observations = listOf(101L, 202L).map { sourceId ->
            evidenceFixture.addonEvidence(
                id = "observation-$sourceId",
                rawLabel = "Vol. 1 Ch. 4",
                externalKey = "$sourceId:/volume/1/chapter/4",
                producerId = "addon-$sourceId",
                volume = 1,
            )
        }
        suspend fun assertMappingsPreserved(initialVariants: Map<Long, String>) {
            for (sourceId in listOf(101L, 202L)) {
                val variant = requireNotNull(
                    legacyChapters.getVariantBySourceIdentity(sourceId, "/volume/1/chapter/4"),
                )
                variant.id shouldBe initialVariants.getValue(sourceId)
                variant.canonicalChapterId shouldBe chapter.id
                evidenceFixture.evidenceRepository.getByProducerExternalKey(
                    ProducerKind.ADDON,
                    "addon-$sourceId",
                    "$sourceId:/volume/1/chapter/4",
                )?.mappedCanonicalChapterId shouldBe chapter.id
            }
            legacyChapters.getByCanonicalTitleId("title").map { it.id } shouldBe listOf(chapter.id)
            evidenceFixture.chapterRepository.getByCanonicalTitleId("title").map { it.id } shouldBe
                listOf(chapter.id)
        }

        legacy.execute(listOf(inventory(101L, true), inventory(202L, true)))
        evidenceFixture.reconciler.execute("title", observations)
        val initialVariants = listOf(101L, 202L).associateWith { sourceId ->
            requireNotNull(legacyChapters.getVariantBySourceIdentity(sourceId, "/volume/1/chapter/4")).id
        }
        assertMappingsPreserved(initialVariants)

        // An empty response from one source must not remove the other source's
        // variant or the empty source's previously established mapping.
        legacy.execute(listOf(inventory(101L, false), inventory(202L, true)))
        evidenceFixture.reconciler.execute("title", observations.drop(1))
        assertMappingsPreserved(initialVariants)

        // An entirely empty accepted inventory is also non-destructive.
        legacy.execute(listOf(inventory(101L, false), inventory(202L, false)))
        evidenceFixture.reconciler.execute("title", emptyList())
        assertMappingsPreserved(initialVariants)
    }

    @Test
    fun `legacy inventory rejects an existing source URL with a contradictory explicit volume`() = runTest {
        val legacyChapters = FakeCanonicalChapterRepository()
        val evidenceFixture = fixture()
        val original = existingChapter("volume-one", volume = 1)
        val replacement = existingChapter("volume-two", volume = 2)
        for (chapter in listOf(original, replacement)) {
            legacyChapters.upsert(chapter)
            evidenceFixture.chapterRepository.upsert(chapter)
        }
        var nextVariant = 0
        val legacy = ReconcileChapterInventory(
            parser = ParseCanonicalChapterLabel(),
            volumeParser = ParseCanonicalChapterVolume(),
            canonicalChapterRepository = legacyChapters,
            idFactory = { error("Both chapter identities are already present") },
            variantIdFactory = { "variant-${++nextVariant}" },
            clock = { 100L },
        )
        fun inventory(sourceId: Long, url: String, label: String) = SourceChapterInventory(
            sourceMappingId = "mapping-${sourceId}",
            sourceId = sourceId,
            canonicalTitleId = "title",
            chapters = listOf(
                SourceChapterSnapshot(
                    sourceId = sourceId,
                    sourceMappingId = "mapping-${sourceId}",
                    sourceChapterId = url,
                    rawName = label,
                    rawNumberHint = 4.0,
                ),
            ),
        )
        val originalRow = inventory(101L, "/reused-key", "Vol. 1 Ch. 4")
        val independentRow = inventory(202L, "/independent", "Vol. 1 Ch. 4")
        legacy.execute(listOf(originalRow, independentRow))
        evidenceFixture.reconciler.execute(
            "title",
            listOf(
                evidenceFixture.addonEvidence(
                    id = "first-observation",
                    rawLabel = "Vol. 1 Ch. 4",
                    externalKey = "101:/reused-key",
                    producerId = "addon-101",
                    volume = 1,
                ),
                evidenceFixture.addonEvidence(
                    id = "independent-observation",
                    rawLabel = "Vol. 1 Ch. 4",
                    externalKey = "202:/independent",
                    producerId = "addon-202",
                    volume = 1,
                ),
            ),
        )
        val firstVariant = requireNotNull(legacyChapters.getVariantBySourceIdentity(101L, "/reused-key"))
        val secondVariant = requireNotNull(legacyChapters.getVariantBySourceIdentity(202L, "/independent"))
        firstVariant.canonicalChapterId shouldBe original.id
        secondVariant.canonicalChapterId shouldBe original.id

        // RED: the old writer currently trusts a reused URL even when its
        // explicit new volume contradicts the previously persisted identity.
        shouldThrow<IllegalStateException> {
            legacy.execute(inventory(101L, "/reused-key", "Vol. 2 Ch. 4"))
        }
        legacyChapters.getVariantBySourceIdentity(101L, "/reused-key") shouldBe firstVariant
        legacyChapters.getVariantBySourceIdentity(202L, "/independent") shouldBe secondVariant
        legacyChapters.getByCanonicalTitleId("title").map { it.id }.toSet() shouldBe
            setOf(original.id, replacement.id)

        evidenceFixture.reconciler.execute(
            "title",
            listOf(
                evidenceFixture.addonEvidence(
                    id = "changed-observation",
                    rawLabel = "Vol. 2 Ch. 4",
                    externalKey = "101:/reused-key",
                    producerId = "addon-101",
                    volume = 2,
                ),
            ),
        )
        evidenceFixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "addon-101",
            "101:/reused-key",
        )?.mappedCanonicalChapterId shouldBe replacement.id
        evidenceFixture.evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "addon-202",
            "202:/independent",
        )?.mappedCanonicalChapterId shouldBe original.id
        evidenceFixture.chapterRepository.getById(original.id)?.confirmation shouldBe
            CanonicalChapterConfirmation.PROVISIONAL
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
                ),
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
    fun `unmapped observation without volume does not join the only explicit volume candidate`() = runTest {
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
        chapters.single().volume shouldBe 1
        fixture.evidenceRepository.getByCanonicalTitleId("title") shouldHaveSize 2
        fixture.evidenceRepository.getByProducerExternalKey(
            producerKind = ProducerKind.ADDON,
            producerId = "source-two",
            externalChapterKey = "source-two-37",
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
            listOf(volumeOne.copy(id = "first-source-refresh", volume = null)),
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
