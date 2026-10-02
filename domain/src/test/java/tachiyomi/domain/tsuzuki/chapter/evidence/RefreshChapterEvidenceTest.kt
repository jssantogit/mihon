package tachiyomi.domain.tsuzuki.chapter.evidence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
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
import tachiyomi.domain.tsuzuki.chapter.refresh.ChapterRefreshSnapshot
import tachiyomi.domain.tsuzuki.chapter.refresh.ChapterRefreshSnapshotRepository
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentOption
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCacheKey
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingConfirmationRequiredException
import tachiyomi.domain.tsuzuki.content.interactor.ContentBindingNotFoundException
import tachiyomi.domain.tsuzuki.content.interactor.DiscoverReadableTitle
import tachiyomi.domain.tsuzuki.content.interactor.RankContentOptions
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshChapterEvidenceTest {

    @Test
    fun `fresh chapter snapshot skips provider until forced refresh`() = runTest {
        var providerCalls = 0
        var now = 1_000L
        val snapshots = FakeChapterRefreshSnapshotRepository()
        val chapters = FakeCanonicalChapterRepository()
        val evidenceRepository = FakeChapterEvidenceRepository()
        val provider = object : ChapterEvidenceProvider {
            override val producerId = "editorial"

            override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> {
                providerCalls++
                return Result.success(emptyList())
            }
        }
        val refresh = RefreshChapterEvidence(
            registry = registry(listOf(provider)),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapters,
                evidenceRepository = evidenceRepository,
            ),
            refreshSnapshots = snapshots,
            clock = { now },
        )

        refresh.execute("canonical-title").isSuccess shouldBe true
        providerCalls shouldBe 1

        now += 1_000L
        refresh.execute("canonical-title").isSuccess shouldBe true
        providerCalls shouldBe 1

        refresh.execute("canonical-title", forceRefresh = true).isSuccess shouldBe true
        providerCalls shouldBe 2
    }

    @Test
    fun `refresh publishes deterministic provider stages before later provider completes`() = runTest {
        val secondGate = CompletableDeferred<Unit>()
        val chapters = FakeCanonicalChapterRepository()
        val stages = mutableListOf<List<String>>()
        val slowProvider = object : ChapterEvidenceProvider {
            override val producerId: String = "second"

            override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> {
                secondGate.await()
                return Result.success(
                    listOf(editorialEvidence("e2", "2", "Chapter 2", producerId)),
                )
            }
        }
        val refresh = refresh(
            chapters = chapters,
            providers = listOf(
                provider(
                    "first",
                    Result.success(listOf(editorialEvidence("e1", "1", "Chapter 1", "first"))),
                ),
                slowProvider,
            ),
        )

        val operation = async {
            refresh.executeProgressively(
                canonicalTitleId = "canonical-title",
                onStageReconciled = {
                    stages += chapters.getByCanonicalTitleId("canonical-title").map { it.displayNumber }
                },
            )
        }
        runCurrent()

        stages shouldContainExactly listOf(listOf("1"))
        operation.isCompleted shouldBe false

        secondGate.complete(Unit)
        operation.await().isSuccess shouldBe true
        stages shouldContainExactly listOf(
            listOf("1"),
            listOf("1", "2"),
        )
    }

    @Test
    fun `later fast provider publishes before earlier slow provider completes`() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val chapters = FakeCanonicalChapterRepository()
        val stages = mutableListOf<List<String>>()
        val slowFirst = object : ChapterEvidenceProvider {
            override val producerId: String = "slow-first"

            override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> {
                firstGate.await()
                return Result.success(
                    listOf(editorialEvidence("slow-1", "1", "Chapter 1", producerId)),
                )
            }
        }
        val fastSecond = provider(
            "fast-second",
            Result.success(listOf(editorialEvidence("fast-2", "2", "Chapter 2", "fast-second"))),
        )
        val refresh = refresh(
            chapters = chapters,
            providers = listOf(slowFirst, fastSecond),
        )

        val operation = async {
            refresh.executeProgressively(
                canonicalTitleId = "canonical-title",
                onStageReconciled = {
                    stages += chapters.getByCanonicalTitleId("canonical-title").map { it.displayNumber }
                },
            )
        }
        runCurrent()

        stages shouldContainExactly listOf(listOf("2"))
        operation.isCompleted shouldBe false

        firstGate.complete(Unit)
        operation.await().isSuccess shouldBe true
        stages.size shouldBe 2
        stages.first() shouldContainExactly listOf("2")
        stages.last().toSet() shouldBe setOf("1", "2")
    }

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
    fun `binding lookup failure is not misreported as extension inventory failure`() = runTest {
        val diagnostics = RecordingDiagnostics()
        val addonId = AddonId("mangafire")
        diagnostics.start("canonical-title")
        val refresh = refreshWithProbe(
            diagnostics = diagnostics,
            addonId = addonId,
            bindingAvailable = false,
            result = Result.success(emptyList()),
            bindingError = ContentBindingNotFoundException("No safe title match"),
        )
        refresh.execute("canonical-title").isSuccess shouldBe true

        val event = diagnostics.events.single()
        event.outcome shouldBe ChapterInventoryDiagnosticOutcome.NO_BINDING
        event.reasons[ChapterInventoryDiagnosticReason.NO_BINDING] shouldBe 1
    }

    @Test
    fun `ambiguous source binding remains an explicit confirmation requirement`() = runTest {
        val diagnostics = RecordingDiagnostics()
        val addonId = AddonId("mangafire")
        diagnostics.start("canonical-title")
        val refresh = refreshWithProbe(
            diagnostics = diagnostics,
            addonId = addonId,
            bindingAvailable = false,
            result = Result.success(emptyList()),
            bindingError = ContentBindingConfirmationRequiredException(emptyList()),
        )

        refresh.execute("canonical-title").isSuccess shouldBe true
        val event = diagnostics.events.single()
        event.outcome shouldBe ChapterInventoryDiagnosticOutcome.PARTIAL
        event.reasons[ChapterInventoryDiagnosticReason.BINDING_CONFIRMATION_REQUIRED] shouldBe 1
    }

    @Test
    fun `diagnostic classifies wrapped timeout and unknown IO without changing refresh result`() = runTest {
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
        val timeoutEvent = diagnostics.events.single {
            it.stage == ChapterInventoryDiagnosticStage.CHAPTER_PROBE
        }
        timeoutEvent.outcome shouldBe ChapterInventoryDiagnosticOutcome.TIMEOUT

        diagnostics.clear()
        diagnostics.start("canonical-title")
        val unknownIoRefresh = refreshWithProbe(
            diagnostics = diagnostics,
            addonId = addonId,
            bindingAvailable = true,
            result = Result.failure(IllegalStateException("wrapper", IOException("private network detail"))),
        )
        unknownIoRefresh.execute("canonical-title").isSuccess shouldBe true
        val ioEvent = diagnostics.events.single {
            it.stage == ChapterInventoryDiagnosticStage.CHAPTER_PROBE
        }
        ioEvent.outcome shouldBe ChapterInventoryDiagnosticOutcome.INDETERMINATE
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
    fun `refresh without existing bindings discovers configured title source before addon probe`() = runTest {
        val addonId = AddonId("preferred-addon")
        val binding = ContentBinding(
            id = "auto-binding",
            canonicalTitleId = "canonical-title",
            addonId = addonId,
            providerTitleKey = "42:/death-note",
            matchConfidence = 1.0,
            verifiedByUser = false,
            availability = ContentBindingAvailability.AVAILABLE,
            runtimePayload = byteArrayOf(1),
            createdAt = 1L,
            updatedAt = 1L,
        )
        var bindingAvailable = false
        var probeCalls = 0
        val discover = mockk<DiscoverReadableTitle>()
        coEvery { discover.execute("canonical-title") } coAnswers {
            bindingAvailable = true
            Result.success(listOf(binding))
        }
        val probe = object : ChapterProbeProvider {
            override val addonId: AddonId = addonId
            override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> {
                probeCalls++
                return Result.success(emptyList())
            }
        }
        val addons = object : AddonRegistry {
            override fun contentProviders(): List<ContentProvider> = emptyList()
            override fun chapterProbeProviders(): List<ChapterProbeProvider> = listOf(probe)
        }
        val resolver = mockk<ResolveContentBinding>()
        coEvery { resolver.existingBindingsForRefresh("canonical-title", addonId) } coAnswers {
            Result.success(if (bindingAvailable) listOf(binding) else emptyList())
        }
        val refresh = RefreshChapterEvidence(
            registry = registry(emptyList()),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = FakeCanonicalChapterRepository(),
                evidenceRepository = FakeChapterEvidenceRepository(),
            ),
            addonRegistry = addons,
            resolveContentBinding = resolver,
            contentOptionCache = ContentOptionCache(),
            diagnostics = NoOpChapterInventoryDiagnostics,
            discoverReadableTitle = discover,
        )

        refresh.execute("canonical-title").isSuccess shouldBe true

        coVerify(exactly = 1) { discover.execute("canonical-title") }
        probeCalls shouldBe 1
    }

    @Test
    fun `empty existing addon inventory broadens binding discovery and reprobes`() = runTest {
        val staleAddon = AddonId("stale")
        val readableAddon = AddonId("readable")
        val staleBinding = ContentBinding(
            id = "stale-binding",
            canonicalTitleId = "canonical-title",
            addonId = staleAddon,
            providerTitleKey = "1:/empty",
            matchConfidence = 1.0,
            verifiedByUser = false,
            availability = ContentBindingAvailability.AVAILABLE,
            runtimePayload = byteArrayOf(1),
            createdAt = 1L,
            updatedAt = 1L,
        )
        val readableBinding = staleBinding.copy(
            id = "readable-binding",
            addonId = readableAddon,
            providerTitleKey = "2:/tokyo-ghoul",
        )
        var readableAvailable = false
        val discover = mockk<DiscoverReadableTitle>()
        coEvery { discover.execute("canonical-title") } returns Result.success(listOf(staleBinding))
        coEvery {
            discover.execute("canonical-title", broadenExistingBindings = true)
        } coAnswers {
            readableAvailable = true
            Result.success(listOf(staleBinding, readableBinding))
        }

        val staleProbe = object : ChapterProbeProvider {
            override val addonId: AddonId = staleAddon
            override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> =
                Result.success(emptyList())
        }
        val readableProbe = object : ChapterProbeProvider {
            override val addonId: AddonId = readableAddon
            override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> =
                Result.success(
                    listOf(
                        ChapterEvidence(
                            id = "tokyo-ghoul-1",
                            canonicalTitleId = canonicalTitleId,
                            producerKind = ProducerKind.ADDON,
                            producerId = readableAddon.value,
                            externalChapterKey = "2:chapter-1",
                            rawLabel = "Chapter 1",
                            rawNumber = 1.0,
                            volume = null,
                            title = null,
                            observedAt = 10L,
                            confidence = 1.0,
                            authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
                        ),
                    ),
                )
        }
        val addons = object : AddonRegistry {
            override fun contentProviders(): List<ContentProvider> = emptyList()
            override fun chapterProbeProviders(): List<ChapterProbeProvider> =
                listOf(staleProbe, readableProbe)
        }
        val resolver = mockk<ResolveContentBinding>()
        coEvery { resolver.existingBindingsForRefresh("canonical-title", staleAddon) } returns
            Result.success(listOf(staleBinding))
        coEvery { resolver.existingBindingsForRefresh("canonical-title", readableAddon) } coAnswers {
            Result.success(if (readableAvailable) listOf(readableBinding) else emptyList())
        }
        val chapters = FakeCanonicalChapterRepository()
        val refresh = RefreshChapterEvidence(
            registry = registry(emptyList()),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapters,
                evidenceRepository = FakeChapterEvidenceRepository(),
            ),
            addonRegistry = addons,
            resolveContentBinding = resolver,
            contentOptionCache = ContentOptionCache(),
            diagnostics = NoOpChapterInventoryDiagnostics,
            discoverReadableTitle = discover,
        )

        refresh.execute("canonical-title").isSuccess shouldBe true

        coVerify(exactly = 1) {
            discover.execute("canonical-title", broadenExistingBindings = true)
        }
        chapters.getByCanonicalTitleId("canonical-title")
            .map { it.displayNumber } shouldContainExactly listOf("1")
    }

    // Physical Tokyo Ghoul regression: safe-but-empty bindings must not block a later readable source.
    @Test
    fun `refresh keeps broadening past multiple empty safe bindings until readable inventory is found`() = runTest {
        val staleAddon = AddonId("stale")
        val emptyAddon = AddonId("empty")
        val readableAddon = AddonId("readable")
        fun binding(id: String, addonId: AddonId, sourceId: Long, path: String) = ContentBinding(
            id = id,
            canonicalTitleId = "canonical-title",
            addonId = addonId,
            providerTitleKey = "$sourceId:$path",
            matchConfidence = 1.0,
            verifiedByUser = false,
            availability = ContentBindingAvailability.AVAILABLE,
            runtimePayload = byteArrayOf(1),
            createdAt = 1L,
            updatedAt = 1L,
        )
        val staleBinding = binding("stale-binding", staleAddon, 1L, "/empty")
        val emptyBinding = binding("empty-binding", emptyAddon, 2L, "/also-empty")
        val readableBinding = binding("readable-binding", readableAddon, 3L, "/tokyo-ghoul")

        var discoveryWave = 0
        val discover = mockk<DiscoverReadableTitle>()
        coEvery { discover.execute("canonical-title") } returns Result.success(listOf(staleBinding))
        coEvery {
            discover.execute("canonical-title", broadenExistingBindings = true)
        } coAnswers {
            discoveryWave++
            when (discoveryWave) {
                1 -> Result.success(listOf(staleBinding, emptyBinding))
                else -> Result.success(listOf(staleBinding, emptyBinding, readableBinding))
            }
        }

        fun emptyProbe(addonId: AddonId) = object : ChapterProbeProvider {
            override val addonId: AddonId = addonId
            override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> =
                Result.success(emptyList())
        }
        val readableProbe = object : ChapterProbeProvider {
            override val addonId: AddonId = readableAddon
            override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> =
                Result.success(
                    listOf(
                        ChapterEvidence(
                            id = "tokyo-ghoul-1",
                            canonicalTitleId = canonicalTitleId,
                            producerKind = ProducerKind.ADDON,
                            producerId = readableAddon.value,
                            externalChapterKey = "3:chapter-1",
                            rawLabel = "Chapter 1",
                            rawNumber = 1.0,
                            volume = null,
                            title = null,
                            observedAt = 10L,
                            confidence = 1.0,
                            authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
                        ),
                    ),
                )
        }
        val addons = object : AddonRegistry {
            override fun contentProviders(): List<ContentProvider> = emptyList()
            override fun chapterProbeProviders(): List<ChapterProbeProvider> =
                listOf(emptyProbe(staleAddon), emptyProbe(emptyAddon), readableProbe)
        }
        val resolver = mockk<ResolveContentBinding>()
        coEvery { resolver.existingBindingsForRefresh("canonical-title", staleAddon) } returns
            Result.success(listOf(staleBinding))
        coEvery { resolver.existingBindingsForRefresh("canonical-title", emptyAddon) } coAnswers {
            Result.success(if (discoveryWave >= 1) listOf(emptyBinding) else emptyList())
        }
        coEvery { resolver.existingBindingsForRefresh("canonical-title", readableAddon) } coAnswers {
            Result.success(if (discoveryWave >= 2) listOf(readableBinding) else emptyList())
        }

        val chapters = FakeCanonicalChapterRepository()
        val refresh = RefreshChapterEvidence(
            registry = registry(emptyList()),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapters,
                evidenceRepository = FakeChapterEvidenceRepository(),
            ),
            addonRegistry = addons,
            resolveContentBinding = resolver,
            contentOptionCache = ContentOptionCache(),
            diagnostics = NoOpChapterInventoryDiagnostics,
            discoverReadableTitle = discover,
        )

        refresh.execute("canonical-title").isSuccess shouldBe true

        coVerify(exactly = 2) {
            discover.execute("canonical-title", broadenExistingBindings = true)
        }
        chapters.getByCanonicalTitleId("canonical-title")
            .map { it.displayNumber } shouldContainExactly listOf("1")
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
    fun `fast Add-on stage publishes before slow integration completes`() = runTest {
        val integrationGate = CompletableDeferred<Unit>()
        val canonicalTitleId = "canonical-title"
        val addonId = AddonId("fast-addon")
        val chapters = FakeCanonicalChapterRepository()
        val stages = mutableListOf<List<String>>()

        val slowIntegration = object : ChapterEvidenceProvider {
            override val producerId: String = "slow-editorial"

            override suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>> {
                integrationGate.await()
                return Result.success(
                    listOf(editorialEvidence("editorial-1", "1", "Chapter 1", producerId)),
                )
            }
        }
        val fastAddon = object : ChapterProbeProvider {
            override val addonId: AddonId = addonId

            override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> =
                Result.success(
                    listOf(
                        ChapterEvidence(
                            id = "addon-2",
                            canonicalTitleId = canonicalTitleId,
                            producerKind = ProducerKind.ADDON,
                            producerId = addonId.value,
                            externalChapterKey = "42:chapter-2",
                            rawLabel = "Chapter 2",
                            rawNumber = 2.0,
                            volume = null,
                            title = null,
                            observedAt = 10L,
                            confidence = 1.0,
                            authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
                        ),
                    ),
                )
        }
        val addons = object : AddonRegistry {
            override fun contentProviders(): List<ContentProvider> = emptyList()
            override fun chapterProbeProviders(): List<ChapterProbeProvider> = listOf(fastAddon)
        }
        val binding = ContentBinding(
            id = "fast-binding",
            canonicalTitleId = canonicalTitleId,
            addonId = addonId,
            providerTitleKey = "42:/fast-title",
            matchConfidence = 1.0,
            verifiedByUser = true,
            availability = ContentBindingAvailability.AVAILABLE,
            runtimePayload = byteArrayOf(1),
            createdAt = 1L,
            updatedAt = 1L,
        )
        val resolver = mockk<ResolveContentBinding>()
        coEvery {
            resolver.existingBindingsForRefresh(canonicalTitleId, addonId)
        } returns Result.success(listOf(binding))
        val refresh = RefreshChapterEvidence(
            registry = registry(listOf(slowIntegration)),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = ParseCanonicalChapterLabel(),
                canonicalChapterRepository = chapters,
                evidenceRepository = FakeChapterEvidenceRepository(),
            ),
            addonRegistry = addons,
            resolveContentBinding = resolver,
            contentOptionCache = ContentOptionCache(),
            diagnostics = NoOpChapterInventoryDiagnostics,
        )

        val operation = async {
            refresh.executeProgressively(
                canonicalTitleId = canonicalTitleId,
                onStageReconciled = {
                    stages += chapters.getByCanonicalTitleId(canonicalTitleId).map { it.displayNumber }
                },
            )
        }
        runCurrent()

        stages shouldContainExactly listOf(listOf("2"))
        operation.isCompleted shouldBe false

        integrationGate.complete(Unit)
        operation.await().isSuccess shouldBe true
        stages.size shouldBe 2
        stages.first() shouldContainExactly listOf("2")
        stages.last().toSet() shouldBe setOf("1", "2")
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
        coEvery { resolver.existingBindingsForRefresh("canonical-title", AddonId("fallback")) } returns
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
    fun `title refresh does not share a prior in-flight content lookup with a new caller`() = runTest {
        val canonicalTitleId = "canonical-title"
        val addonId = AddonId("fixture-addon")
        val cache = ContentOptionCache()
        val inFlight = InFlightContentResolution(backgroundScope)
        val chapters = FakeCanonicalChapterRepository()
        val evidence = FakeChapterEvidenceRepository()
        val reconcile = ReconcileChapterEvidence(
            parser = ParseCanonicalChapterLabel(),
            canonicalChapterRepository = chapters,
            evidenceRepository = evidence,
            idFactory = { "canonical-chapter-1" },
            clock = { 100L },
        )

        fun inventoryEvidence(observedAt: Long) = ChapterEvidence(
            id = "fixture-chapter-1",
            canonicalTitleId = canonicalTitleId,
            producerKind = ProducerKind.ADDON,
            producerId = addonId.value,
            externalChapterKey = "/chapter/1",
            rawLabel = "Chapter 1",
            rawNumber = 1.0,
            volume = null,
            title = null,
            observedAt = observedAt,
            confidence = 1.0,
            authority = ChapterEvidenceAuthority.ADDON_PROVISIONAL,
        )

        reconcile.execute(canonicalTitleId, listOf(inventoryEvidence(observedAt = 1L)))
        val canonicalChapterId = chapters.getByCanonicalTitleId(canonicalTitleId).single().id
        val staleProviderStarted = CompletableDeferred<Unit>()
        val releaseStaleProvider = CompletableDeferred<Unit>()
        var providerCalls = 0
        val contentProvider = object : ContentProvider {
            override val addonId: AddonId = addonId

            override suspend fun resolve(
                canonicalTitleId: String,
                canonicalChapterId: String,
            ): Result<List<ContentOption>> {
                providerCalls++
                val revision = evidence.getByCanonicalTitleId(canonicalTitleId)
                    .single().evidence.observedAt
                if (providerCalls == 1) {
                    staleProviderStarted.complete(Unit)
                    releaseStaleProvider.await()
                }
                return Result.success(
                    listOf(
                        ContentOption(
                            key = "revision-$revision",
                            canonicalChapterId = canonicalChapterId,
                            addonId = addonId,
                            language = "en",
                            scanlationGroup = null,
                            releaseDate = null,
                            delivery = ContentDelivery.LocalArchive("fixture://revision-$revision"),
                        ),
                    ),
                )
            }
        }
        val probeProvider = object : ChapterProbeProvider {
            override val addonId: AddonId = addonId

            override suspend fun probe(canonicalTitleId: String): Result<List<ChapterEvidence>> =
                Result.success(listOf(inventoryEvidence(observedAt = 2L)))
        }
        val addonRegistry = object : AddonRegistry {
            override fun contentProviders(): List<ContentProvider> = listOf(contentProvider)
            override fun chapterProbeProviders(): List<ChapterProbeProvider> = listOf(probeProvider)
        }
        val binding = ContentBinding(
            id = "fixture-binding",
            canonicalTitleId = canonicalTitleId,
            addonId = addonId,
            providerTitleKey = "fixture-title",
            matchConfidence = 1.0,
            verifiedByUser = true,
            availability = ContentBindingAvailability.AVAILABLE,
            runtimePayload = byteArrayOf(1),
            createdAt = 1L,
            updatedAt = 1L,
        )
        val bindingResolver = mockk<ResolveContentBinding>()
        coEvery {
            bindingResolver.existingBindingsForRefresh(canonicalTitleId, addonId)
        } returns Result.success(listOf(binding))
        val titleDiscovery = mockk<DiscoverReadableTitle>()
        coEvery { titleDiscovery.execute(canonicalTitleId) } returns Result.success(emptyList())
        val refresh = RefreshChapterEvidence(
            registry = registry(emptyList()),
            reconcileChapterEvidence = reconcile,
            addonRegistry = addonRegistry,
            resolveContentBinding = bindingResolver,
            contentOptionCache = cache,
            inFlightContentResolution = inFlight,
            diagnostics = NoOpChapterInventoryDiagnostics,
            discoverReadableTitle = titleDiscovery,
            structuredDiagnostics = NoOpStructuredDiagnosticRecorder,
        )
        val preferenceRepository = object : ContentPreferenceRepository {
            override suspend fun get(canonicalTitleId: String): ContentPreference? = null
            override fun observe(canonicalTitleId: String): Flow<ContentPreference?> = MutableStateFlow(null)
            override suspend fun upsert(preference: ContentPreference) = Unit
            override suspend fun delete(canonicalTitleId: String) = Unit
        }
        val selector = ResolveChapterContent(
            addonRegistry = addonRegistry,
            contentPreferenceRepository = preferenceRepository,
            readerPreferences = CanonicalReaderPreferences(InMemoryPreferenceStore()),
            rankContentOptions = RankContentOptions(),
            contentOptionCache = cache,
            inFlightContentResolution = inFlight,
        )

        val priorLookup = async { selector.lookupOptions(canonicalTitleId, canonicalChapterId) }
        runCurrent()
        staleProviderStarted.await()

        refresh.execute(canonicalTitleId).isSuccess shouldBe true
        evidence.getByCanonicalTitleId(canonicalTitleId).single().evidence.observedAt shouldBe 2L

        val lookupAfterRefresh = async { selector.lookupOptions(canonicalTitleId, canonicalChapterId) }
        runCurrent()
        releaseStaleProvider.complete(Unit)

        val priorResult = priorLookup.await()
        val refreshedResult = lookupAfterRefresh.await()
        priorResult.options shouldBe emptyList()
        refreshedResult.options.single().delivery shouldBe
            ContentDelivery.LocalArchive("fixture://revision-2")
        refreshedResult.failedProviders shouldBe emptyList()
        providerCalls shouldBe 2
        cache.get(
            ContentOptionCacheKey(canonicalTitleId, canonicalChapterId, addonId),
        )?.single()?.delivery shouldBe ContentDelivery.LocalArchive("fixture://revision-2")
    }

    @Test
    fun `post-refresh invalidation clears cache when caller is cancelled between owners`() = runTest {
        val canonicalTitleId = "canonical-title"
        val canonicalChapterId = "canonical-chapter-1"
        val addonId = AddonId("fixture-addon")
        val key = ContentOptionCacheKey(canonicalTitleId, canonicalChapterId, addonId)
        val cache = ContentOptionCache()
        val inFlight = InFlightContentResolution(backgroundScope)
        val staleOption = ContentOption(
            key = "revision-1",
            canonicalChapterId = canonicalChapterId,
            addonId = addonId,
            language = "en",
            scanlationGroup = null,
            releaseDate = null,
            delivery = ContentDelivery.LocalArchive("fixture://revision-1"),
        )
        val freshOption = staleOption.copy(
            key = "revision-2",
            delivery = ContentDelivery.LocalArchive("fixture://revision-2"),
        )
        cache.put(key, listOf(staleOption))

        val providerStarted = CompletableDeferred<Unit>()
        val releaseProvider = CompletableDeferred<Unit>()
        var providerCalls = 0
        val oldWaiter = async {
            inFlight.execute(key) {
                providerCalls++
                providerStarted.complete(Unit)
                releaseProvider.await()
                Result.success(listOf(staleOption))
            }
        }
        runCurrent()
        providerStarted.await()

        val inFlightInvalidated = CompletableDeferred<Unit>()
        val allowCacheInvalidation = CompletableDeferred<Unit>()
        val cleanup = launch {
            invalidateContentOptionsAfterChapterRefresh(
                invalidateInFlight = {
                    inFlight.invalidateTitle(canonicalTitleId)
                    inFlightInvalidated.complete(Unit)
                    allowCacheInvalidation.await()
                },
                invalidateCache = { cache.invalidateTitle(canonicalTitleId) },
            )
        }
        inFlightInvalidated.await()

        cleanup.cancel()
        allowCacheInvalidation.complete(Unit)
        cleanup.join()
        releaseProvider.complete(Unit)
        runCurrent()

        cleanup.isCancelled shouldBe true
        oldWaiter.await().isFailure shouldBe true
        cache.get(key) shouldBe null

        val freshWaiter = async {
            inFlight.execute(key) {
                providerCalls++
                Result.success(listOf(freshOption))
            }
        }
        runCurrent()
        freshWaiter.await().getOrThrow().single().delivery shouldBe freshOption.delivery
        providerCalls shouldBe 2
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
        bindingError: Throwable? = null,
        onResolver: (ResolveContentBinding) -> Unit = {},
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
        onResolver(resolver)
        val bindingResult: Result<List<ContentBinding>> = bindingError?.let { Result.failure(it) }
            ?: Result.success(if (bindingAvailable) listOf(mockk<ContentBinding>()) else emptyList())
        coEvery { resolver.existingBindingsForRefresh("canonical-title", addonId) } returns bindingResult

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

    private class FakeChapterRefreshSnapshotRepository : ChapterRefreshSnapshotRepository {
        private val values = linkedMapOf<Pair<String, String>, ChapterRefreshSnapshot>()

        override suspend fun get(canonicalTitleId: String, scopeKey: String): ChapterRefreshSnapshot? =
            values[canonicalTitleId to scopeKey]

        override suspend fun upsertIfNewer(snapshot: ChapterRefreshSnapshot): ChapterRefreshSnapshot {
            val key = snapshot.canonicalTitleId to snapshot.scopeKey
            val existing = values[key]
            if (existing == null || snapshot.observedAt > existing.observedAt ||
                (snapshot.observedAt == existing.observedAt && snapshot.fingerprint == existing.fingerprint)
            ) {
                values[key] = snapshot
            }
            return values.getValue(key)
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
