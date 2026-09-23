package tachiyomi.domain.tsuzuki.chapter.evidence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.addon.ContentProvider
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
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
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
    fun `diagnostic distinguishes timeout empty inventory and absent binding`() = runTest {
        val diagnostics = RecordingDiagnostics()
        val addonId = AddonId("mangafire")

        diagnostics.start("canonical-title")
        val timeoutRefresh = refreshWithProbe(
            diagnostics = diagnostics,
            addonId = addonId,
            bindingAvailable = true,
            result = Result.failure(SocketTimeoutException("sensitive network detail")),
        )
        timeoutRefresh.execute("canonical-title").isSuccess shouldBe true
        diagnostics.events.single().outcome shouldBe ChapterInventoryDiagnosticOutcome.TIMEOUT

        diagnostics.clear()
        diagnostics.start("canonical-title")
        val emptyRefresh = refreshWithProbe(
            diagnostics = diagnostics,
            addonId = addonId,
            bindingAvailable = true,
            result = Result.success(emptyList()),
        )
        emptyRefresh.execute("canonical-title").isSuccess shouldBe true
        diagnostics.events.single().outcome shouldBe ChapterInventoryDiagnosticOutcome.EMPTY

        diagnostics.clear()
        diagnostics.start("canonical-title")
        val noBindingRefresh = refreshWithProbe(
            diagnostics = diagnostics,
            addonId = addonId,
            bindingAvailable = false,
            result = Result.success(emptyList()),
        )
        noBindingRefresh.execute("canonical-title").isSuccess shouldBe true
        val noBindingEvent = diagnostics.events.single()
        noBindingEvent.outcome shouldBe ChapterInventoryDiagnosticOutcome.NO_BINDING
        noBindingEvent.reasons[ChapterInventoryDiagnosticReason.NO_BINDING] shouldBe 1
        diagnostics.report().contains("sensitive network detail") shouldBe false
    }

    @Test
    fun `diagnostic classifies wrapped timeout and IO causes without changing refresh result`() = runTest {
        val diagnostics = RecordingDiagnostics()
        val addonId = AddonId("mangafire")

        diagnostics.start("canonical-title")
        val timeoutRefresh = refreshWithProbe(
            diagnostics = diagnostics,
            addonId = addonId,
            bindingAvailable = true,
            result = Result.failure(
                IllegalStateException("wrapper", SocketTimeoutException("private timeout")),
            ),
        )
        timeoutRefresh.execute("canonical-title").isSuccess shouldBe true
        val timeoutEvent = diagnostics.events.single { it.stage == ChapterInventoryDiagnosticStage.PROBE }
        timeoutEvent.outcome shouldBe ChapterInventoryDiagnosticOutcome.TIMEOUT

        diagnostics.clear()
        diagnostics.start("canonical-title")
        val networkRefresh = refreshWithProbe(
            diagnostics = diagnostics,
            addonId = addonId,
            bindingAvailable = true,
            result = Result.failure(IllegalStateException("wrapper", IOException("private network detail"))),
        )
        networkRefresh.execute("canonical-title").isSuccess shouldBe true
        val networkEvent = diagnostics.events.single { it.stage == ChapterInventoryDiagnosticStage.PROBE }
        networkEvent.outcome shouldBe ChapterInventoryDiagnosticOutcome.NETWORK_ERROR
        diagnostics.report().contains("private network detail") shouldBe false
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
    fun `editorial refresh bounds simultaneous provider requests`() = runTest {
        val unblock = CompletableDeferred<Unit>()
        var concurrent = 0
        var peak = 0
        val providers = (1..12).map { index ->
            object : ChapterEvidenceProvider {
                override val producerId = "integration-$index"

                override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> {
                    concurrent++
                    peak = maxOf(peak, concurrent)
                    try {
                        unblock.await()
                        return Result.success(emptyList())
                    } finally {
                        concurrent--
                    }
                }
            }
        }
        val refresh = refresh(FakeCanonicalChapterRepository(), providers)

        val pending = async { refresh.execute("canonical-title") }
        runCurrent()
        peak shouldBe 4
        unblock.complete(Unit)
        advanceUntilIdle()

        pending.await().isSuccess shouldBe true
        concurrent shouldBe 0
    }

    @Test
    fun `integration and Add-on evidence fetch concurrently before reconciliation`() = runTest {
        val integrationStarted = CompletableDeferred<Unit>()
        val addonStarted = CompletableDeferred<Unit>()
        val editorial = object : ChapterEvidenceProvider {
            override val producerId = "editorial"

            override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> {
                integrationStarted.complete(Unit)
                addonStarted.await()
                return Result.success(listOf(editorialEvidence("editorial-1", "1", "Chapter 1")))
            }
        }
        val probe = object : ChapterProbeProvider {
            override val addonId = AddonId("fallback")

            override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> {
                addonStarted.complete(Unit)
                integrationStarted.await()
                return Result.success(emptyList())
            }
        }
        val addons = object : AddonRegistry {
            override fun contentProviders(): List<ContentProvider> = emptyList()
            override fun chapterProbeProviders(): List<ChapterProbeProvider> = listOf(probe)
        }
        val resolver = mockk<ResolveContentBinding>()
        coEvery { resolver.executeAll("canonical-title", AddonId("fallback")) } returns
            Result.success(listOf(mockk<ContentBinding>()))
        val chapters = FakeCanonicalChapterRepository()
        val refresh = RefreshChapterEvidence(
            registry = registry(listOf(editorial)),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapters,
                evidenceRepository = FakeChapterEvidenceRepository(),
                idFactory = { "chapter-1" },
                clock = { 100L },
            ),
            addonRegistry = addons,
            resolveContentBinding = resolver,
            contentOptionCache = ContentOptionCache(),
            diagnostics = NoOpChapterInventoryDiagnostics,
        )

        val pending = async { refresh.execute("canonical-title") }
        withTimeout(1_000L) {
            pending.await().isSuccess shouldBe true
        }
        chapters.getByCanonicalTitleId("canonical-title")
            .map { it.displayNumber } shouldContainExactly listOf("1")
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

    private fun refreshWithProbe(
        diagnostics: RecordingDiagnostics,
        addonId: AddonId,
        bindingAvailable: Boolean,
        result: Result<List<ChapterEvidence>>,
    ): RefreshChapterEvidence {
        val probe = object : ChapterProbeProvider {
            override val addonId: AddonId = addonId

            override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> = result
        }
        val addonRegistry = object : AddonRegistry {
            override fun contentProviders(): List<ContentProvider> = emptyList()
            override fun chapterProbeProviders(): List<ChapterProbeProvider> = listOf(probe)
        }
        val resolver = mockk<ResolveContentBinding>()
        coEvery { resolver.executeAll("canonical-title", addonId) } returns
            Result.success(if (bindingAvailable) listOf(mockk<ContentBinding>()) else emptyList())

        return RefreshChapterEvidence(
            registry = registry(emptyList()),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = FakeCanonicalChapterRepository(),
                evidenceRepository = FakeChapterEvidenceRepository(),
            ),
            addonRegistry = addonRegistry,
            resolveContentBinding = resolver,
            contentOptionCache = ContentOptionCache(),
            diagnostics = diagnostics,
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
