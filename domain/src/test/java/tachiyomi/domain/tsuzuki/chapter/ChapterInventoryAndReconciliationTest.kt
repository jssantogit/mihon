package tachiyomi.domain.tsuzuki.chapter

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceAuthority
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceWrite
import tachiyomi.domain.tsuzuki.chapter.evidence.LegacyInventoryEvidenceAdapter
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileLegacyChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ChapterMutationGate
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterVolume
import tachiyomi.domain.tsuzuki.chapter.interactor.ReconcileChapterInventory
import tachiyomi.domain.tsuzuki.chapter.interactor.RefreshCanonicalChapters
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapterType
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.chapter.service.ChapterInventoryGateway
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class ChapterInventoryAndReconciliationTest {

    @Test
    fun `same specific identity from two mappings shares one canonical chapter and keeps both variants`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        val first = reconciler.execute(inventory(mappingId = "mapping-1", sourceId = 1L, name = "Chapter 12"))
        val second = reconciler.execute(inventory(mappingId = "mapping-2", sourceId = 2L, name = "Ch. 012"))

        first.canonicalChapters.map { it.id } shouldBe listOf("chapter-1")
        second.canonicalChapters.map { it.id } shouldBe listOf("chapter-1")
        repository.getByCanonicalTitleId("title-1").size shouldBe 1
        repository.getVariantsByCanonicalChapterId("chapter-1")
            .map { it.sourceMappingId } shouldContainExactly listOf("mapping-1", "mapping-2")
    }

    @Test
    fun `multiple inventories reconcile through one atomic batch and share new canonical identities`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        val report = reconciler.execute(
            listOf(
                inventory(mappingId = "mapping-1", sourceId = 1L, name = "Chapter 12", sourceChapterId = "/one"),
                inventory(mappingId = "mapping-2", sourceId = 2L, name = "Ch. 012", sourceChapterId = "/two"),
            ),
        )

        report.canonicalChapters.map { it.id } shouldBe listOf("chapter-1")
        report.variants.map { it.sourceMappingId } shouldContainExactly listOf("mapping-1", "mapping-2")
        report.sourceMappingIds shouldBe setOf("mapping-1", "mapping-2")
        repository.upsertBatchCalls shouldBe 1
        repository.getByCanonicalTitleId("title-1").size shouldBe 1
    }

    @Test
    fun `unqualified chapter does not join an explicit volume and both variants remain`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        reconciler.execute(inventory("mapping-1", 1L, "Vol.1 Ch.1 - Um Soco", "/md/1"))
        reconciler.execute(inventory("mapping-2", 2L, "Chapter 1", "/mf/1"))

        val chapters = repository.getByCanonicalTitleId("title-1")
        chapters.size shouldBe 2
        chapters.single { it.volume == 1 }.baseNumber shouldBe 1
        val unqualifiedChapter = chapters.single { it.volume == null }
        unqualifiedChapter.baseNumber shouldBe 1
        repository.getVariantBySourceIdentity(1L, "/md/1")?.canonicalChapterId shouldBe
            chapters.single { it.volume == 1 }.id
        repository.getVariantBySourceIdentity(2L, "/mf/1")?.canonicalChapterId shouldBe
            unqualifiedChapter.id
    }

    @Test
    fun `explicit inventory volume selects the matching canonical chapter id`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        repository.chapters["chapter-volume-1"] = canonicalChapter("chapter-volume-1", volume = 1)
        repository.chapters["chapter-volume-2"] = canonicalChapter("chapter-volume-2", volume = 2)
        val reconciler = reconciler(repository)

        val report = reconciler.execute(inventory("mapping-2", 2L, "Vol.2 Ch.1", "/md/vol2/ch1"))

        report.canonicalChapters.map { it.id } shouldBe listOf("chapter-volume-2")
        report.createdCanonicalChapterIds shouldBe emptySet()
        repository.getVariantBySourceIdentity(2L, "/md/vol2/ch1")?.canonicalChapterId shouldBe "chapter-volume-2"
        repository.getById("chapter-volume-1")?.volume shouldBe 1
        repository.getById("chapter-volume-2")?.volume shouldBe 2
    }

    @Test
    fun `duplicate canonical candidates for one explicit volume are not selected arbitrarily`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        repository.chapters["chapter-volume-2-a"] = canonicalChapter("chapter-volume-2-a", volume = 2)
        repository.chapters["chapter-volume-2-b"] = canonicalChapter("chapter-volume-2-b", volume = 2)
        val reconciler = reconciler(repository)

        val report = reconciler.execute(inventory("mapping-2", 2L, "Vol.2 Ch.1", "/md/vol2/ch1"))

        report.createdCanonicalChapterIds.size shouldBe 1
        val newChapterId = report.canonicalChapters.single().id
        (newChapterId in setOf("chapter-volume-2-a", "chapter-volume-2-b")) shouldBe false
        repository.getVariantBySourceIdentity(2L, "/md/vol2/ch1")?.canonicalChapterId shouldBe newChapterId
    }

    @Test
    fun `ambiguous explicit volume does not fall back to an unqualified candidate`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        repository.chapters["chapter-unqualified"] = canonicalChapter("chapter-unqualified", volume = null)
        val reconciler = reconciler(repository)

        val report = reconciler.execute(
            inventory("mapping-1", 1L, "Vol.1 Ch.1 - Vol.2 edition", "/md/ambiguous/ch1"),
        )

        report.createdCanonicalChapterIds.size shouldBe 1
        val newChapterId = report.canonicalChapters.single().id
        newChapterId shouldBe report.createdCanonicalChapterIds.single()
        repository.getVariantBySourceIdentity(1L, "/md/ambiguous/ch1")?.canonicalChapterId shouldBe newChapterId
        repository.getById("chapter-unqualified")?.volume shouldBe null
    }

    @Test
    fun `incompatible type part suffix and numbered semantic labels stay separate`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        reconciler.execute(inventory("mapping-1", 1L, "Chapter 12"))
        reconciler.execute(inventory("mapping-2", 2L, "12.5"))
        reconciler.execute(inventory("mapping-3", 3L, "12a"))
        reconciler.execute(inventory("mapping-4", 4L, "Extra 12"))
        reconciler.execute(inventory("mapping-5", 5L, "Prologue 1"))
        reconciler.execute(inventory("mapping-6", 6L, "Prologue 2"))

        repository.getByCanonicalTitleId("title-1").map { it.identity } shouldContainExactly listOf(
            repository.getByCanonicalTitleId("title-1")[0].identity,
            repository.getByCanonicalTitleId("title-1")[1].identity,
            repository.getByCanonicalTitleId("title-1")[2].identity,
            repository.getByCanonicalTitleId("title-1")[3].identity,
            repository.getByCanonicalTitleId("title-1")[4].identity,
            repository.getByCanonicalTitleId("title-1")[5].identity,
        )
        repository.getByCanonicalTitleId("title-1").size shouldBe 6
    }

    @Test
    fun `unknown labels from different sources are not heuristically merged`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        reconciler.execute(inventory("mapping-1", 1L, "Bonus chapter", sourceChapterId = "/one"))
        reconciler.execute(inventory("mapping-2", 2L, "Bonus chapter", sourceChapterId = "/two"))

        repository.getByCanonicalTitleId("title-1").size shouldBe 2
    }

    @Test
    fun `reused source identity with a different reliable chapter number fails without changing its old mapping`() =
        runTest {
            val repository = FakeCanonicalChapterRepository()
            val reconciler = reconciler(repository)

            reconciler.execute(inventory("mapping-1", 1L, "Chapter 12", sourceChapterId = "/chapter"))
            val originalVariant = repository.getVariantBySourceIdentity(1L, "/chapter")
            shouldThrow<IllegalStateException> {
                reconciler.execute(inventory("mapping-1", 1L, "Chapter 99", sourceChapterId = "/chapter"))
            }

            repository.getByCanonicalTitleId("title-1").size shouldBe 1
            repository.getVariantBySourceIdentity(1L, "/chapter") shouldBe originalVariant
            repository.getById("chapter-1")?.baseNumber shouldBe 12
        }

    @Test
    fun `refresh never deletes variants absent from the current source inventory`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        reconciler.execute(
            SourceChapterInventory(
                sourceMappingId = "mapping-1",
                sourceId = 1L,
                canonicalTitleId = "title-1",
                chapters = listOf(
                    snapshot(1L, "mapping-1", "Chapter 1", "/one"),
                    snapshot(1L, "mapping-1", "Chapter 2", "/two"),
                ),
            ),
        )
        reconciler.execute(
            SourceChapterInventory(
                sourceMappingId = "mapping-1",
                sourceId = 1L,
                canonicalTitleId = "title-1",
                chapters = listOf(snapshot(1L, "mapping-1", "Chapter 1", "/one")),
            ),
        )

        repository.getVariantBySourceIdentity(1L, "/two") shouldBe ChapterVariant(
            id = "variant-2",
            canonicalChapterId = "chapter-2",
            sourceMappingId = "mapping-1",
            sourceId = 1L,
            sourceChapterId = "/two",
            sourceChapterUrl = "/two",
            rawName = "Chapter 2",
            language = "en",
            createdAt = 100L,
            updatedAt = 100L,
        )
    }

    @Test
    fun `reconciling one mapping does not alter another mapping variants`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        reconciler.execute(inventory("mapping-1", 1L, "Chapter 1", "/one"))
        reconciler.execute(inventory("mapping-2", 2L, "Chapter 1", "/two"))
        val mappingOneBefore = repository.getVariantBySourceIdentity(1L, "/one")
        val mappingTwoBefore = repository.getVariantBySourceIdentity(2L, "/two")

        shouldThrow<IllegalStateException> {
            reconciler.execute(inventory("mapping-1", 1L, "Chapter 9", "/one"))
        }

        repository.getVariantBySourceIdentity(1L, "/one") shouldBe mappingOneBefore
        repository.getVariantBySourceIdentity(2L, "/two") shouldBe mappingTwoBefore
        repository.getVariantsBySourceMappingId("mapping-2").size shouldBe 1
    }

    @Test
    fun `transactional batch failure leaves canonical state without partial writes`() = runTest {
        val repository = FailingTransactionalCanonicalChapterRepository()
        val reconciler = reconciler(repository)
        repository.failBatch = true

        shouldThrow<IllegalStateException> {
            reconciler.execute(inventory("mapping-1", 1L, "Chapter 1", "/one"))
        }

        repository.chapters shouldBe emptyMap()
        repository.variants shouldBe emptyMap()
    }

    @Test
    fun `multi mapping refresh batch failure leaves every mapping unpersisted`() = runTest {
        val repository = FailingTransactionalCanonicalChapterRepository()
        val gateway = FakeChapterInventoryGateway()
        val mappings = FakeSourceTitleMappingRepository(
            mapping("mapping-1", materialized = true),
            mapping("mapping-2", materialized = true),
        )
        gateway.inventories = mapOf(
            "mapping-1" to inventory("mapping-1", 1L, "Chapter 1", "/one"),
            "mapping-2" to inventory("mapping-2", 2L, "Chapter 2", "/two"),
        )
        repository.failBatch = true

        val refresh = refresh(mappings, gateway, repository)
        refresh.execute("title-1", mappingIds = listOf("mapping-1", "mapping-2")).isFailure shouldBe true

        repository.upsertBatchCalls shouldBe 1
        repository.chapters shouldBe emptyMap()
        repository.variants shouldBe emptyMap()
    }

    @Test
    fun `source failure leaves persisted state intact and cancellation propagates`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val gateway = FakeChapterInventoryGateway()
        val refresh = refresh(
            sourceTitleMappingRepository = FakeSourceTitleMappingRepository(mapping("mapping-1", true)),
            chapterInventoryGateway = gateway,
            canonicalChapterRepository = repository,
        )

        gateway.result = Result.success(inventory("mapping-1", 1L, "Chapter 1", "/one"))
        refresh.execute("title-1").isSuccess shouldBe true
        val chaptersBeforeFailure = repository.chapters.toMap()
        val variantsBeforeFailure = repository.variants.toMap()

        gateway.result = Result.failure(IllegalStateException("network"))
        refresh.execute("title-1").isFailure shouldBe true
        repository.chapters shouldBe chaptersBeforeFailure
        repository.variants shouldBe variantsBeforeFailure

        gateway.result = Result.failure(CancellationException("cancelled"))
        shouldThrow<CancellationException> { refresh.execute("title-1") }
    }

    @Test
    fun `refresh defaults to one eligible preferred mapping and explicit subset can broaden atomically`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val gateway = FakeChapterInventoryGateway()
        val mappings = FakeSourceTitleMappingRepository(
            mapping("mapping-1", materialized = true, preferred = false),
            mapping("mapping-2", materialized = true, preferred = true),
            mapping("mapping-3", materialized = true, preferred = false),
        )
        gateway.inventories = mapOf(
            "mapping-1" to inventory("mapping-1", 1L, "Chapter 1"),
            "mapping-2" to inventory("mapping-2", 2L, "Chapter 2"),
            "mapping-3" to inventory("mapping-3", 3L, "Chapter 3"),
        )
        val refresh = refresh(mappings, gateway, repository)

        refresh.execute("title-1").getOrThrow().sourceMappingIds shouldBe setOf("mapping-2")

        refresh.execute("title-1", mappingIds = listOf("mapping-1", "mapping-3"))
            .getOrThrow().sourceMappingIds shouldBe setOf("mapping-1", "mapping-3")
        gateway.requestedMappingIds shouldBe listOf("mapping-2", "mapping-1", "mapping-3")
    }

    @Test
    fun `refresh skips unavailable mappings and rejects explicitly unavailable mapping`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val gateway = FakeChapterInventoryGateway()
        val unavailablePreferred = mapping("mapping-1", materialized = true, preferred = true).copy(
            availability = SourceMappingAvailability.UNAVAILABLE,
        )
        val available = mapping("mapping-2", materialized = true)
        val mappings = FakeSourceTitleMappingRepository(unavailablePreferred, available)
        gateway.inventories = mapOf(
            "mapping-2" to inventory("mapping-2", 2L, "Chapter 2"),
        )
        val refresh = refresh(mappings, gateway, repository)

        refresh.execute("title-1").getOrThrow().sourceMappingIds shouldBe setOf("mapping-2")
        gateway.requestedMappingIds shouldContainExactly listOf("mapping-2")

        gateway.requestedMappingIds.clear()
        refresh.execute("title-1", mappingId = "mapping-1").isFailure shouldBe true
        gateway.requestedMappingIds shouldBe emptyList()
    }

    @Test
    fun `production refresh constructor routes materialized inventory through staged evidence writer`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val mappings = FakeSourceTitleMappingRepository(mapping("mapping-1", materialized = true))
        val gateway = FakeChapterInventoryGateway().apply {
            result = Result.success(
                inventory("mapping-1", 1L, "Chapter 4", "/chapter/4").copy(
                    mihonMangaId = 10L,
                    sourceUrl = "/mapping-1",
                    fetchStartedAtMillis = 100L,
                ),
            )
        }
        repository.upsert(canonicalChapter("chapter-cutover", volume = null).copy(displayNumber = "4", baseNumber = 4))
        val staged = mockk<ReconcileLegacyChapterEvidence>()
        coEvery { staged.execute(any(), any()) } returns
            listOf(
                ChapterVariant(
                    id = "variant-cutover",
                    canonicalChapterId = "chapter-cutover",
                    sourceMappingId = "mapping-1",
                    sourceId = 1L,
                    mihonMangaId = 10L,
                    mihonChapterId = null,
                    sourceChapterId = "/chapter/4",
                    sourceChapterUrl = "/chapter/4",
                    language = "en",
                    scanlationGroup = null,
                    version = null,
                    releaseDate = null,
                    rawName = "Chapter 4",
                    rawNumberHint = 4.0,
                    rawSourceOrder = null,
                    rawSourceMetadata = kotlinx.serialization.json.buildJsonObject {},
                    createdAt = 100L,
                    updatedAt = 100L,
                ),
            )
        val refresh = RefreshCanonicalChapters(mappings, gateway, staged, repository)

        val report = refresh.execute("title-1", mappingId = "mapping-1").getOrThrow()

        report.sourceMappingIds shouldBe setOf("mapping-1")
        io.mockk.coVerify(exactly = 1) { staged.execute(any(), any()) }
        report.canonicalChapters.map { it.id } shouldBe listOf("chapter-cutover")
        report.variants.map { it.id } shouldBe listOf("variant-cutover")
    }

    @Test
    fun `legacy refresh must wait for an active shared evidence mutation gate`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val sharedGate = ChapterMutationGate()
        val mappings = FakeSourceTitleMappingRepository(mapping("mapping-1", materialized = true))
        val gateway = FakeChapterInventoryGateway().apply {
            result = Result.success(inventory("mapping-1", 1L, "Chapter 4", "/chapter/4"))
        }
        val legacyRefresh = refresh(mappings, gateway, repository, sharedGate)
        val gateEntered = CompletableDeferred<Unit>()
        val releaseGate = CompletableDeferred<Unit>()
        val competingReconciliation = async {
            sharedGate.withLock {
                gateEntered.complete(Unit)
                releaseGate.await()
            }
        }
        gateEntered.await()
        val pendingRefresh = async { legacyRefresh.execute("title-1", mappingId = "mapping-1") }
        try {
            runCurrent()
            pendingRefresh.isCompleted shouldBe false
            repository.chapters shouldBe emptyMap()
            repository.variants shouldBe emptyMap()
        } finally {
            releaseGate.complete(Unit)
        }
        competingReconciliation.await()
        pendingRefresh.await().isSuccess shouldBe true
        repository.getByCanonicalTitleId("title-1").single().displayNumber shouldBe "4"
        repository.getVariantsBySourceMappingId("mapping-1").size shouldBe 1
    }

    @Test
    fun `simultaneous legacy Reader and editorial refresh retain one canonical chapter and stable variant`() =
        runTest {
            val sharedGate = ChapterMutationGate()
            val legacyInsideWrite = CompletableDeferred<Unit>()
            val finishLegacyWrite = CompletableDeferred<Unit>()
            var blockFirstLegacyWrite = true
            val repository = object : FakeCanonicalChapterRepository() {
                override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) {
                    if (blockFirstLegacyWrite && (chapters.isNotEmpty() || variants.isNotEmpty())) {
                        blockFirstLegacyWrite = false
                        legacyInsideWrite.complete(Unit)
                        finishLegacyWrite.await()
                    }
                    super.upsertBatch(chapters, variants)
                }
            }
            val evidenceRecords = linkedMapOf<String, PersistedChapterEvidence>()
            val evidenceRepository = object : ChapterEvidenceRepository {
                override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<PersistedChapterEvidence> =
                    evidenceRecords.values.filter { it.evidence.canonicalTitleId == canonicalTitleId }

                override suspend fun getByProducerExternalKey(
                    producerKind: ProducerKind,
                    producerId: String,
                    externalChapterKey: String,
                ): PersistedChapterEvidence? = evidenceRecords.values.firstOrNull {
                    it.evidence.producerKind == producerKind &&
                        it.evidence.producerId == producerId &&
                        it.evidence.externalChapterKey == externalChapterKey
                }

                override suspend fun upsert(
                    evidence: ChapterEvidence,
                    mappedCanonicalChapterId: String?,
                ): PersistedChapterEvidence =
                    PersistedChapterEvidence(evidence, mappedCanonicalChapterId).also {
                        evidenceRecords[evidence.id] = it
                    }
            }
            val editorialReconciler = ReconcileChapterEvidence(
                ParseCanonicalChapterLabel(),
                repository,
                evidenceRepository,
                sharedGate,
                NoOpChapterInventoryDiagnostics,
            )
            val sourceMapping = mapping("mapping-1", materialized = true)
            val sourceMappings = FakeSourceTitleMappingRepository(sourceMapping)
            val baseInventory = inventory("mapping-1", 1L, "Vol. 1 Ch. 4", "/chapter/4")
            val sourceInventory = baseInventory.copy(
                mihonMangaId = sourceMapping.mihonMangaId,
                sourceUrl = sourceMapping.sourceUrl,
                chapters = baseInventory.chapters.map { it.copy(mihonMangaId = sourceMapping.mihonMangaId) },
            )
            val gateway = object : ChapterInventoryGateway {
                override suspend fun fetch(mapping: SourceTitleMapping): Result<SourceChapterInventory> =
                    Result.success(sourceInventory)
            }
            val legacyReconciler = ReconcileLegacyChapterEvidence(
                adapter = LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
                reconciler = editorialReconciler,
                chapters = repository,
                sourceMappings = sourceMappings,
            )
            val legacyRefresh = RefreshCanonicalChapters(
                sourceMappings,
                gateway,
                legacyReconciler,
                repository,
                { 100L },
            )
            val editorial = object : ChapterEvidenceProvider {
                override val producerId = "editorial"

                override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> =
                    Result.success(
                        listOf(
                            ChapterEvidence(
                                id = "editorial-vol-1-ch-4",
                                canonicalTitleId = canonicalTitleId,
                                producerKind = ProducerKind.INTEGRATION,
                                producerId = producerId,
                                externalChapterKey = "/editorial/1/4",
                                rawLabel = "Vol. 1 Ch. 4",
                                rawNumber = 4.0,
                                volume = 1,
                                title = null,
                                observedAt = 100L,
                                confidence = 1.0,
                                authority = ChapterEvidenceAuthority.EDITORIAL,
                            ),
                        ),
                    )
            }
            val registry = mockk<IntegrationRegistry>()
            coEvery { registry.awaitReady() } returns Unit
            every { registry.chapterEvidenceProviders() } returns listOf(editorial)
            val editorialRefresh = RefreshChapterEvidence(registry, editorialReconciler)

            val pendingLegacy = async { legacyRefresh.execute("title-1", mappingId = "mapping-1") }
            legacyInsideWrite.await()
            val pendingEditorial = async { editorialRefresh.execute("title-1") }
            try {
                runCurrent()
                pendingEditorial.isCompleted shouldBe false
                repository.chapters shouldBe emptyMap()
                repository.variants shouldBe emptyMap()
            } finally {
                finishLegacyWrite.complete(Unit)
            }
            pendingLegacy.await().isSuccess shouldBe true
            pendingEditorial.await().isSuccess shouldBe true

            val canonicalId = repository.getByCanonicalTitleId("title-1").single().id
            val firstVariant = requireNotNull(repository.getVariantBySourceIdentity(1L, "/chapter/4"))
            firstVariant.canonicalChapterId shouldBe canonicalId
            evidenceRecords.values.size shouldBe 2
            evidenceRecords.values.map { it.mappedCanonicalChapterId }.toSet() shouldBe setOf(canonicalId)
            repository.getByCanonicalTitleId("title-1").single().confirmation shouldBe
                CanonicalChapterConfirmation.CONFIRMED

            // Repeat both entrypoints after the competing refresh to prove
            // that editorial confirmation never forks the operational variant.
            editorialRefresh.execute("title-1").isSuccess shouldBe true
            legacyRefresh.execute("title-1", mappingId = "mapping-1").isSuccess shouldBe true
            repository.getByCanonicalTitleId("title-1").map { it.id } shouldBe listOf(canonicalId)
            repository.getVariantBySourceIdentity(1L, "/chapter/4")?.id shouldBe firstVariant.id
            repository.getVariantsBySourceMappingId("mapping-1").size shouldBe 1
        }

    @Test
    fun `reconciliation rejects mismatched or blank source identity evidence`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val reconciler = reconciler(repository)

        shouldThrow<IllegalArgumentException> {
            reconciler.execute(
                inventory("mapping-1", 1L, "Chapter 1").copy(
                    chapters = listOf(snapshot(2L, "mapping-1", "Chapter 1", "/one")),
                ),
            )
        }
        shouldThrow<IllegalArgumentException> {
            reconciler.execute(
                inventory("mapping-1", 1L, "Chapter 1").copy(
                    chapters = listOf(snapshot(1L, "mapping-2", "Chapter 1", "/one")),
                ),
            )
        }
        shouldThrow<IllegalArgumentException> {
            reconciler.execute(
                inventory("mapping-1", 1L, "Chapter 1").copy(
                    chapters = listOf(snapshot(1L, "mapping-1", "Chapter 1", "")),
                ),
            )
        }

        repository.chapters shouldBe emptyMap()
        repository.variants shouldBe emptyMap()
    }

    @Test
    fun `newer mapped evidence suppresses stale low confidence and equal time conflicting inventory`() = runTest {
        val repository = FakeCanonicalChapterRepository()
        val chapterTwo = canonicalChapter("chapter-2", volume = null).copy(
            displayNumber = "2",
            baseNumber = 2,
        )
        repository.upsert(chapterTwo)
        var persistedObservation = PersistedChapterEvidence(
            evidence = ChapterEvidence(
                id = "chapter-two-evidence",
                canonicalTitleId = "title-1",
                producerKind = ProducerKind.ADDON,
                producerId = "addon",
                externalChapterKey = "1:/chapter/shared",
                rawLabel = "Chapter 2",
                rawNumber = 2.0,
                volume = null,
                title = null,
                observedAt = 101L,
                confidence = 1.0,
                authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
            ),
            mappedCanonicalChapterId = chapterTwo.id,
        )
        val evidenceRepository = object : ChapterEvidenceRepository {
            override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<PersistedChapterEvidence> =
                listOf(persistedObservation).filter { it.evidence.canonicalTitleId == canonicalTitleId }

            override suspend fun getByProducerExternalKey(
                producerKind: ProducerKind,
                producerId: String,
                externalChapterKey: String,
            ): PersistedChapterEvidence? = persistedObservation.takeIf {
                it.evidence.producerKind == producerKind &&
                    it.evidence.producerId == producerId &&
                    it.evidence.externalChapterKey == externalChapterKey
            }

            override suspend fun upsert(
                evidence: ChapterEvidence,
                mappedCanonicalChapterId: String?,
            ): PersistedChapterEvidence = error("Inventory freshness check must not write evidence")
        }
        val reconciler = ReconcileChapterInventory(
            parser = ParseCanonicalChapterLabel(),
            volumeParser = ParseCanonicalChapterVolume(),
            canonicalChapterRepository = repository,
            mutationGate = ChapterMutationGate(),
            chapterEvidenceRepository = evidenceRepository,
        )

        ParseCanonicalChapterLabel().execute("Omake").identity.isSpecific shouldBe false

        suspend fun reconcile(rawLabel: String, fetchStartedAtMillis: Long) {
            reconciler.execute(
                inventory("mapping-1", 1L, rawLabel, "/chapter/shared").copy(
                    sourceUrl = "/manga/one-punch-man",
                    fetchStartedAtMillis = fetchStartedAtMillis,
                ),
            ).variants shouldBe emptyList()
            repository.chapters.keys shouldBe setOf(chapterTwo.id)
            repository.variants shouldBe emptyMap()
        }

        reconcile(rawLabel = "Omake", fetchStartedAtMillis = 100L)

        persistedObservation = persistedObservation.copy(
            evidence = persistedObservation.evidence.copy(observedAt = 100L),
        )
        reconcile(rawLabel = "Chapter 1", fetchStartedAtMillis = 100L)

        persistedObservation = persistedObservation.copy(
            evidence = persistedObservation.evidence.copy(observedAt = 101L),
            mappedCanonicalChapterId = null,
        )
        reconcile(rawLabel = "Chapter 1", fetchStartedAtMillis = 100L)
    }

    @Test
    fun `unmapped newer evidence blocks stale inventory but not a later fetch`() = runTest {
        val chapterTwo = canonicalChapter("chapter-2", volume = null).copy(
            displayNumber = "2",
            baseNumber = 2,
        )
        val repository = FakeCanonicalChapterRepository().also { it.upsert(chapterTwo) }
        val observation = PersistedChapterEvidence(
            evidence = ChapterEvidence(
                id = "unmapped-chapter-two-evidence",
                canonicalTitleId = "title-1",
                producerKind = ProducerKind.ADDON,
                producerId = "addon",
                externalChapterKey = "1:/chapter/shared",
                rawLabel = "Chapter 2",
                rawNumber = 2.0,
                volume = null,
                title = null,
                observedAt = 101L,
                confidence = 1.0,
                authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
            ),
            mappedCanonicalChapterId = null,
        )
        val evidenceRepository = evidenceRepository(listOf(observation))
        val reconciler = reconcilerWithEvidence(repository, evidenceRepository)

        reconciler.execute(
            inventory("mapping-1", 1L, "Chapter 1", "/chapter/shared").copy(
                sourceUrl = "/manga/one-punch-man",
                fetchStartedAtMillis = 100L,
            ),
        )
        repository.chapters.keys shouldBe setOf(chapterTwo.id)
        repository.variants shouldBe emptyMap()

        reconciler.execute(
            inventory("mapping-1", 1L, "Chapter 1", "/chapter/shared").copy(
                sourceUrl = "/manga/one-punch-man",
                fetchStartedAtMillis = 102L,
            ),
        )
        repository.chapters.keys shouldBe setOf(chapterTwo.id, "chapter-1")
        repository.getVariantBySourceIdentity(1L, "/chapter/shared")?.canonicalChapterId shouldBe "chapter-1"
    }

    @Test
    fun `conflicting tied evidence is rejected regardless of order and identical replay is accepted`() = runTest {
        val chapterOne = canonicalChapter("chapter-1", volume = null).copy(
            displayNumber = "1",
            baseNumber = 1,
        )
        val chapterTwo = canonicalChapter("chapter-2", volume = null).copy(
            displayNumber = "2",
            baseNumber = 2,
        )
        val chapterOneObservation = persistedAddonObservation(
            id = "chapter-one-observation",
            rawLabel = "Chapter 1",
            mappedChapterId = chapterOne.id,
        )
        val chapterTwoObservation = persistedAddonObservation(
            id = "chapter-two-observation",
            rawLabel = "Chapter 2",
            mappedChapterId = chapterTwo.id,
        )

        for (observations in listOf(
            listOf(chapterOneObservation, chapterTwoObservation),
            listOf(chapterTwoObservation, chapterOneObservation),
        )) {
            val repository = FakeCanonicalChapterRepository().also {
                it.upsert(chapterOne)
                it.upsert(chapterTwo)
            }
            val reconciler = reconcilerWithEvidence(repository, evidenceRepository(observations))

            reconciler.execute(
                inventory("mapping-1", 1L, "Chapter 1", "/chapter/shared").copy(
                    sourceUrl = "/manga/one-punch-man",
                    fetchStartedAtMillis = 100L,
                ),
            )

            repository.chapters.keys shouldBe setOf(chapterOne.id, chapterTwo.id)
            repository.variants shouldBe emptyMap()
        }

        val replayRepository = FakeCanonicalChapterRepository().also { it.upsert(chapterTwo) }
        val replayEvidence = chapterTwoObservation.copy(
            evidence = chapterTwoObservation.evidence.copy(id = "chapter-two-replay"),
        )
        val replayReconciler = reconcilerWithEvidence(
            replayRepository,
            evidenceRepository(listOf(chapterTwoObservation, replayEvidence)),
        )

        replayReconciler.execute(
            inventory("mapping-1", 1L, "Chapter 2", "/chapter/shared").copy(
                sourceUrl = "/manga/one-punch-man",
                fetchStartedAtMillis = 100L,
            ),
        )

        replayRepository.chapters.keys shouldBe setOf(chapterTwo.id)
        replayRepository.getVariantBySourceIdentity(1L, "/chapter/shared")?.canonicalChapterId shouldBe chapterTwo.id
    }

    private fun evidenceRepository(
        observations: List<PersistedChapterEvidence>,
    ): ChapterEvidenceRepository = object : ChapterEvidenceRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<PersistedChapterEvidence> =
            observations.filter { it.evidence.canonicalTitleId == canonicalTitleId }

        override suspend fun getByProducerExternalKey(
            producerKind: ProducerKind,
            producerId: String,
            externalChapterKey: String,
        ): PersistedChapterEvidence? = observations.firstOrNull {
            it.evidence.producerKind == producerKind &&
                it.evidence.producerId == producerId &&
                it.evidence.externalChapterKey == externalChapterKey
        }

        override suspend fun upsert(
            evidence: ChapterEvidence,
            mappedCanonicalChapterId: String?,
        ): PersistedChapterEvidence = error("Inventory freshness check must not write evidence")
    }

    private fun persistedAddonObservation(
        id: String,
        rawLabel: String,
        mappedChapterId: String,
    ) = PersistedChapterEvidence(
        evidence = ChapterEvidence(
            id = id,
            canonicalTitleId = "title-1",
            producerKind = ProducerKind.ADDON,
            producerId = "addon",
            externalChapterKey = "1:/chapter/shared",
            rawLabel = rawLabel,
            rawNumber = rawLabel.removePrefix("Chapter ").toDouble(),
            volume = null,
            title = null,
            observedAt = 101L,
            confidence = 1.0,
            authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
        ),
        mappedCanonicalChapterId = mappedChapterId,
    )

    private fun reconcilerWithEvidence(
        repository: FakeCanonicalChapterRepository,
        evidenceRepository: ChapterEvidenceRepository,
    ) = ReconcileChapterInventory(
        parser = ParseCanonicalChapterLabel(),
        volumeParser = ParseCanonicalChapterVolume(),
        canonicalChapterRepository = repository,
        idFactory = object : () -> String {
            private var next = 0
            override fun invoke(): String = "chapter-${++next}"
        },
        variantIdFactory = object : () -> String {
            private var next = 0
            override fun invoke(): String = "variant-${++next}"
        },
        clock = { 100L },
        mutationGate = ChapterMutationGate(),
        chapterEvidenceRepository = evidenceRepository,
    )

    private fun reconciler(repository: FakeCanonicalChapterRepository) = ReconcileChapterInventory(
        parser = ParseCanonicalChapterLabel(),
        volumeParser = ParseCanonicalChapterVolume(),
        canonicalChapterRepository = repository,
        idFactory = object : () -> String {
            private var next = 0
            override fun invoke(): String = "chapter-${++next}"
        },
        variantIdFactory = object : () -> String {
            private var next = 0
            override fun invoke(): String = "variant-${++next}"
        },
        clock = { 100L },
    )

    private fun refresh(
        sourceTitleMappingRepository: FakeSourceTitleMappingRepository,
        chapterInventoryGateway: ChapterInventoryGateway,
        canonicalChapterRepository: FakeCanonicalChapterRepository,
        mutationGate: ChapterMutationGate = ChapterMutationGate(),
        evidenceRepository: FakeChapterEvidenceRepository = FakeChapterEvidenceRepository(),
    ): RefreshCanonicalChapters {
        val staged = ReconcileLegacyChapterEvidence(
            adapter = LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
            reconciler = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = canonicalChapterRepository,
                evidenceRepository = evidenceRepository,
                idFactory = object : () -> String {
                    private var next = 0
                    override fun invoke(): String = "chapter-${++next}"
                },
                clock = { 100L },
                mutationGate = mutationGate,
            ),
            chapters = canonicalChapterRepository,
            sourceMappings = sourceTitleMappingRepository,
        )
        val materializingGateway = object : ChapterInventoryGateway {
            override suspend fun fetch(mapping: SourceTitleMapping): Result<SourceChapterInventory> =
                chapterInventoryGateway.fetch(mapping).map { inventory ->
                    inventory.copy(
                        mihonMangaId = inventory.mihonMangaId ?: mapping.mihonMangaId,
                        sourceUrl = inventory.sourceUrl.ifBlank { mapping.sourceUrl },
                        chapters = inventory.chapters.map { snapshot ->
                            if (snapshot.mihonMangaId == null) {
                                snapshot.copy(mihonMangaId = mapping.mihonMangaId)
                            } else {
                                snapshot
                            }
                        },
                    )
                }
        }
        return RefreshCanonicalChapters(
            sourceTitleMappingRepository = sourceTitleMappingRepository,
            chapterInventoryGateway = materializingGateway,
            reconcileLegacyChapterEvidence = staged,
            canonicalChapterRepository = canonicalChapterRepository,
            clock = { 100L },
        )
    }

    private fun inventory(
        mappingId: String,
        sourceId: Long,
        name: String,
        sourceChapterId: String = "/$mappingId/${name.replace(' ', '-')}",
    ) = SourceChapterInventory(
        sourceMappingId = mappingId,
        sourceId = sourceId,
        canonicalTitleId = "title-1",
        chapters = listOf(snapshot(sourceId, mappingId, name, sourceChapterId)),
    )

    private fun snapshot(sourceId: Long, mappingId: String, name: String, url: String) =
        SourceChapterSnapshot(
            sourceId = sourceId,
            sourceMappingId = mappingId,
            sourceChapterId = url,
            sourceChapterUrl = url,
            rawName = name,
            language = "en",
            rawNumberHint = null,
            rawSourceOrder = null,
            scanlationGroup = null,
            releaseDate = null,
            createdAt = 100L,
            updatedAt = 100L,
        )

    private fun canonicalChapter(id: String, volume: Int?) = CanonicalChapter(
        id = id,
        canonicalTitleId = "title-1",
        displayNumber = "1",
        volume = volume,
        type = CanonicalChapterType.REGULAR,
        baseNumber = 1,
        confidence = 1.0,
        createdAt = 50L,
        updatedAt = 50L,
    )

    private fun mapping(id: String, materialized: Boolean, preferred: Boolean = false) = SourceTitleMapping(
        id = id,
        canonicalTitleId = "title-1",
        mihonMangaId = if (materialized) 10L else null,
        sourceId = id.removePrefix("mapping-").toLong(),
        sourceUrl = "/$id",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = false,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = preferred,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private class FakeChapterInventoryGateway : ChapterInventoryGateway {
        var result: Result<SourceChapterInventory> = Result.success(
            SourceChapterInventory(
                sourceMappingId = "mapping-1",
                sourceId = 1L,
                canonicalTitleId = "title-1",
                chapters = emptyList(),
            ),
        )
        var inventories: Map<String, SourceChapterInventory> = emptyMap()
        val requestedMappingIds = mutableListOf<String>()

        override suspend fun fetch(mapping: SourceTitleMapping): Result<SourceChapterInventory> {
            requestedMappingIds += mapping.id
            return inventories[mapping.id]?.let(Result.Companion::success) ?: result
        }
    }

    private open class FakeCanonicalChapterRepository : CanonicalChapterRepository {
        val chapters = linkedMapOf<String, CanonicalChapter>()
        val variants = linkedMapOf<String, ChapterVariant>()
        var upsertBatchCalls = 0

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.values.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? = chapters[id]

        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? =
            variants.values.firstOrNull { it.sourceId == sourceId && it.sourceChapterId == sourceChapterId }

        override suspend fun getVariantsByCanonicalChapterId(canonicalChapterId: String): List<ChapterVariant> =
            variants.values.filter { it.canonicalChapterId == canonicalChapterId }

        override suspend fun getVariantsBySourceMappingId(sourceMappingId: String): List<ChapterVariant> =
            variants.values.filter { it.sourceMappingId == sourceMappingId }

        override suspend fun upsert(chapter: CanonicalChapter) {
            chapters[chapter.id] = chapter
        }

        override suspend fun upsertVariant(variant: ChapterVariant) {
            variants[variant.id] = variant
        }

        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) {
            upsertBatchCalls += 1
            chapters.forEach { upsert(it) }
            variants.forEach { upsertVariant(it) }
        }
    }

    private class FailingTransactionalCanonicalChapterRepository : FakeCanonicalChapterRepository() {
        var failBatch = false

        override suspend fun upsertBatch(chapters: List<CanonicalChapter>, variants: List<ChapterVariant>) {
            val chaptersBefore = this.chapters.toMap()
            val variantsBefore = this.variants.toMap()
            try {
                super.upsertBatch(chapters, variants)
                if (failBatch) throw IllegalStateException("transaction rolled back")
            } catch (error: Throwable) {
                this.chapters.clear()
                this.chapters.putAll(chaptersBefore)
                this.variants.clear()
                this.variants.putAll(variantsBefore)
                throw error
            }
        }
    }

    private class FakeSourceTitleMappingRepository(
        vararg initial: SourceTitleMapping,
    ) : SourceTitleMappingRepository {
        private val mappings = initial.associateBy { it.id }.toMutableMap()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(mappings.values.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            mappings.values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) {
            mappings[mapping.id] = mapping
        }

        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) =
            Unit
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
        ): PersistedChapterEvidence = persist(evidence, mappedCanonicalChapterId)

        override suspend fun upsertBatch(writes: List<ChapterEvidenceWrite>): List<PersistedChapterEvidence> =
            writes.map { persist(it.evidence, it.mappedCanonicalChapterId) }

        private fun persist(evidence: ChapterEvidence, mappedCanonicalChapterId: String?): PersistedChapterEvidence {
            val index = records.indexOfFirst {
                it.evidence.producerKind == evidence.producerKind &&
                    it.evidence.producerId == evidence.producerId &&
                    it.evidence.externalChapterKey == evidence.externalChapterKey
            }
            val stableEvidence = evidence.copy(id = records.getOrNull(index)?.evidence?.id ?: evidence.id)
            val persisted = PersistedChapterEvidence(stableEvidence, mappedCanonicalChapterId)
            if (index >= 0) records[index] = persisted else records += persisted
            return persisted
        }
    }
}
