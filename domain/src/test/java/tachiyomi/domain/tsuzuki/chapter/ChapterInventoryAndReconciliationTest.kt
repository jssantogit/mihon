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
    fun `production refresh materializes missing variant through staged writer`() = runTest {
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
        val evidenceRepository = FakeChapterEvidenceRepository()
        val staged = ReconcileLegacyChapterEvidence(
            adapter = LegacyInventoryEvidenceAdapter(ParseCanonicalChapterVolume()),
            reconciler = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = repository,
                evidenceRepository = evidenceRepository,
                idFactory = { "chapter-cutover" },
                clock = { 100L },
            ),
            chapters = repository,
            sourceMappings = mappings,
        )
        val refresh = RefreshCanonicalChapters(mappings, gateway, staged, repository)

        repository.getByCanonicalTitleId("title-1") shouldBe emptyList()
        repository.getVariantBySourceIdentity(1L, "/chapter/4") shouldBe null

        val report = refresh.execute("title-1", mappingId = "mapping-1").getOrThrow()

        report.sourceMappingIds shouldBe setOf("mapping-1")
        report.canonicalChapters.map { it.id } shouldBe listOf("chapter-cutover")
        val variant = report.variants.single()
        variant.sourceMappingId shouldBe "mapping-1"
        variant.sourceId shouldBe 1L
        variant.sourceChapterId shouldBe "/chapter/4"
        variant.canonicalChapterId shouldBe "chapter-cutover"
        repository.getVariantBySourceIdentity(1L, "/chapter/4")?.id shouldBe variant.id
        evidenceRepository.getByProducerExternalKey(
            ProducerKind.ADDON,
            "mihon-legacy:title-1:1",
            "1:/chapter/4",
        )?.mappedCanonicalChapterId shouldBe "chapter-cutover"

        val replay = refresh.execute("title-1", mappingId = "mapping-1").getOrThrow()
        replay.variants.single().id shouldBe variant.id
        evidenceRepository.getByCanonicalTitleId("title-1").size shouldBe 1
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
