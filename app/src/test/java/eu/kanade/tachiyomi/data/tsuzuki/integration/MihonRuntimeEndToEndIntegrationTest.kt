package eu.kanade.tachiyomi.data.tsuzuki.integration

import eu.kanade.tachiyomi.data.tsuzuki.MihonChapterInventoryGateway
import eu.kanade.tachiyomi.data.tsuzuki.addon.DefaultAddonRegistry
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonAddonProviderFactory
import eu.kanade.tachiyomi.data.tsuzuki.addon.MihonContentBindingPayloadCodec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.addon.repository.AddonSourceEligibilityRepository
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticEvent
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticOutcome
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticReason
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnosticStage
import tachiyomi.domain.tsuzuki.chapter.diagnostics.ChapterInventoryDiagnostics
import tachiyomi.domain.tsuzuki.chapter.evidence.CanonicalChapterConfirmation
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidenceRepository
import tachiyomi.domain.tsuzuki.chapter.evidence.PersistedChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.ProducerKind
import tachiyomi.domain.tsuzuki.chapter.evidence.ReconcileChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.evidence.RefreshChapterEvidence
import tachiyomi.domain.tsuzuki.chapter.interactor.ParseCanonicalChapterLabel
import tachiyomi.domain.tsuzuki.chapter.model.CanonicalChapter
import tachiyomi.domain.tsuzuki.chapter.model.ChapterVariant
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.ContentPreference
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.cache.InFlightContentResolution
import tachiyomi.domain.tsuzuki.content.interactor.RankContentOptions
import tachiyomi.domain.tsuzuki.content.interactor.ResolveChapterContent
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.content.repository.ContentPreferenceRepository
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import java.util.concurrent.atomic.AtomicLong

class MihonRuntimeEndToEndIntegrationTest {

    @Test
    fun `local HTTP journey discovers source binds canonical title reconciles inventory and offers content`() =
        runTest {
            LocalMihonSourceHarness().use { harness ->
                val canonicalTitleId = "canonical-opm"
                enqueueJourney(harness)
                val journey = RuntimeJourney(harness, canonicalTitleId)

                journey.installedSources().map { it.sourceId } shouldBe listOf(harness.source.id)
                val binding = journey.bind().single()
                binding.canonicalTitleId shouldBe canonicalTitleId
                binding.availability shouldBe ContentBindingAvailability.AVAILABLE
                journey.bindings.getByTitle(canonicalTitleId).single() shouldBe binding
                MihonContentBindingPayloadCodec.decode(binding.runtimePayload).mihonMangaId shouldBe 9001L

                journey.refresh()

                val reconciled = journey.canonicalChapters.getByCanonicalTitleId(canonicalTitleId).single()
                reconciled.displayNumber shouldBe "1"
                reconciled.confirmation shouldBe CanonicalChapterConfirmation.PROVISIONAL
                journey.evidence.getByCanonicalTitleId(canonicalTitleId).single()
                    .mappedCanonicalChapterId shouldBe reconciled.id

                val option = journey.options(reconciled.id).single()
                option.canonicalChapterId shouldBe reconciled.id
                option.addonId shouldBe journey.addonId
                option.language shouldBe "en"
                option.delivery shouldBe ContentDelivery.Mihon(
                    sourceId = harness.source.id,
                    mangaId = 9001L,
                    chapterId = journey.chapterRows.onlyRow().id,
                )
                harness.server.requestCount shouldBe 2
            }
        }

    @Test
    fun `same chapter from two languages keeps both verified source options`() = runTest {
        LocalMihonSourceHarness(languages = listOf("en", "pt-BR")).use { harness ->
            harness.sources.forEach { source ->
                enqueueJourney(harness, source.lang)
            }
            val journey = RuntimeJourney(harness, "canonical-opm-multilingual")
            journey.bind().size shouldBe 2
            journey.refresh()

            val chapter = journey.canonicalChapters.getByCanonicalTitleId(journey.canonicalTitleId).single()
            val persistedEvidence = journey.evidence.getByCanonicalTitleId(journey.canonicalTitleId)
            persistedEvidence.size shouldBe 2
            persistedEvidence.map { it.mappedCanonicalChapterId }.toSet() shouldBe setOf(chapter.id)
            val options = journey.options(chapter.id)

            options.size shouldBe 2
            options.map { it.canonicalChapterId }.toSet() shouldBe setOf(chapter.id)
            options.map { it.language }.toSet() shouldBe setOf("en", "pt-BR")
            journey.bindings.getByTitle(journey.canonicalTitleId).map { it.canonicalTitleId }.toSet() shouldBe
                setOf(journey.canonicalTitleId)
        }
    }

    @Test
    fun `unsafe title match does not create a binding`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            harness.enqueue(body = "/manga/unrelated\tNaruto")
            harness.enqueue(body = "/manga/unrelated\tNaruto")
            val journey = RuntimeJourney(harness, "canonical-opm-unsafe")

            journey.bindResult().isFailure shouldBe true
            journey.bindings.getByTitle(journey.canonicalTitleId) shouldBe emptyList()
            harness.server.requestCount shouldBe 2
        }
    }

    @Test
    fun `binding is reused on refresh without repeating title search`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            enqueueJourney(harness)
            harness.enqueue(body = "")
            val journey = RuntimeJourney(harness, "canonical-opm-reuse")
            val first = journey.bind().single()
            val requestsAfterFirstBinding = harness.server.requestCount

            journey.bind().single() shouldBe first
            journey.refresh()
            harness.server.requestCount shouldBe requestsAfterFirstBinding + 1
        }
    }

    @Test
    fun `empty HTTP inventory is distinguished from transport failure and offers no option`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            harness.enqueue(body = "/manga/one-punch-man\tOne-Punch Man")
            harness.enqueue(body = "")
            val journey = RuntimeJourney(harness, "canonical-opm-empty")
            journey.bind()
            journey.refresh()

            journey.canonicalChapters.getByCanonicalTitleId(journey.canonicalTitleId) shouldBe emptyList()
            journey.options("chapter-not-present") shouldBe emptyList()
            journey.diagnostics.events.single {
                it.stage == ChapterInventoryDiagnosticStage.CHAPTER_INVENTORY
            }.let { event ->
                event.outcome shouldBe ChapterInventoryDiagnosticOutcome.EMPTY
                event.reasons shouldBe mapOf(ChapterInventoryDiagnosticReason.INVENTORY_EMPTY to 1)
            }
        }
    }

    @Test
    fun `malformed inventory remains a parsing error rather than empty`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            harness.enqueue(body = "/manga/one-punch-man\tOne-Punch Man")
            harness.enqueue(body = "malformed")
            val journey = RuntimeJourney(harness, "canonical-opm-malformed")
            journey.bind()
            journey.refresh()

            journey.canonicalChapters.getByCanonicalTitleId(journey.canonicalTitleId) shouldBe emptyList()
            journey.diagnostics.events.single {
                it.stage == ChapterInventoryDiagnosticStage.CHAPTER_INVENTORY
            }.outcome shouldBe ChapterInventoryDiagnosticOutcome.MALFORMED_RESPONSE
        }
    }

    @Test
    fun `one source failure does not hide chapter option from another language source`() = runTest {
        LocalMihonSourceHarness(languages = listOf("en", "pt-BR")).use { harness ->
            harness.sources.forEach { source ->
                harness.enqueue(body = "/manga/one-punch-man\tOne-Punch Man", language = source.lang)
            }
            harness.enqueue(status = 503, body = "server_error", language = "en")
            harness.enqueue(body = "/chapter/1\tChapter 1\t1\tFixture Group", language = "pt-BR")
            val journey = RuntimeJourney(harness, "canonical-opm-partial-source")
            journey.bind()
            journey.refresh()

            val chapter = journey.canonicalChapters.getByCanonicalTitleId(journey.canonicalTitleId).single()
            val options = journey.options(chapter.id)
            options.map { it.language } shouldBe listOf("pt-BR")
            journey.diagnostics.events.any {
                it.stage == ChapterInventoryDiagnosticStage.CHAPTER_INVENTORY &&
                    it.outcome == ChapterInventoryDiagnosticOutcome.HTTP_ERROR && it.language == "en"
            } shouldBe true
        }
    }

    @Test
    fun `repeat inventory refresh preserves canonical chapter identity and binding`() = runTest {
        LocalMihonSourceHarness().use { harness ->
            enqueueJourney(harness)
            harness.enqueue(body = "/chapter/1\tChapter 1\t1\tFixture Group")
            val journey = RuntimeJourney(harness, "canonical-opm-repeat")
            val originalBinding = journey.bind().single()
            journey.refresh()
            val firstChapter = journey.canonicalChapters.getByCanonicalTitleId(journey.canonicalTitleId).single()

            journey.refresh()
            val secondChapter = journey.canonicalChapters.getByCanonicalTitleId(journey.canonicalTitleId).single()

            secondChapter.id shouldBe firstChapter.id
            journey.bindings.getByTitle(journey.canonicalTitleId).single() shouldBe originalBinding
            harness.server.requestCount shouldBe 3
        }
    }

    private fun enqueueJourney(harness: LocalMihonSourceHarness, language: String = harness.source.lang) {
        harness.enqueue(body = "/manga/one-punch-man\tOne-Punch Man", language = language)
        harness.enqueue(body = "/chapter/1\tChapter 1\t1\tFixture Group", language = language)
    }

    private class RuntimeJourney(
        private val harness: LocalMihonSourceHarness,
        val canonicalTitleId: String,
    ) {
        val addonId = AddonId("fixture-addon")
        val chapterRows = InMemoryMihonChapters()
        val bindings = InMemoryContentBindings()
        val canonicalChapters = InMemoryCanonicalChapters()
        val evidence = InMemoryChapterEvidence()
        val diagnostics = RecordingDiagnostics(canonicalTitleId)
        val addon = InstalledAddon(
            id = addonId,
            displayName = "Fixture Add-on",
            enabled = true,
            versionName = "test",
            mihonSourceIds = harness.sources.map { it.id },
            hasSettings = false,
        )
        private val addons = SingleAddonRepository(addon)
        private val persistedManga = mutableMapOf<Long, Manga>()
        private val nextMangaId = AtomicLong(9001L)
        private val titleRepository = mockk<CanonicalTitleRepository> {
            coEvery { getById(canonicalTitleId) } returns CanonicalTitle(
                id = canonicalTitleId,
                displayTitle = "One-Punch Man",
                identityState = CanonicalIdentityState.RESOLVED,
                createdAt = 1L,
                updatedAt = 1L,
            )
        }

        private val parser = ParseCanonicalChapterLabel()
        private val registry: DefaultAddonRegistry
        private val bindingResolver: ResolveContentBinding
        private val refresh: RefreshChapterEvidence
        private val selector: ResolveChapterContent

        init {
            diagnostics.start(canonicalTitleId)
            everyInsertAndReadManga()
            val chapterGateway = MihonChapterInventoryGateway(
                mangaRepository = harness.mangaRepository,
                chapterRepository = chapterRows.repository,
                sourceManager = harness.sourceManager,
                diagnostics = diagnostics,
            )
            val providerFactory = MihonAddonProviderFactory(
                contentBindingRepository = bindings,
                canonicalChapterRepository = canonicalChapters,
                chapterEvidenceRepository = evidence,
                parser = parser,
                chapterInventoryGateway = chapterGateway,
                chapterInventoryDiagnostics = diagnostics,
            )
            val provider = providerFactory.contentProvider(addonId)
            registry = DefaultAddonRegistry(
                installedAddons = { listOf(addon) },
                contentProviderCandidates = listOf(provider),
                chapterProbeProviderCandidates = listOf(providerFactory.chapterProbeProvider(addonId)),
            )
            bindingResolver = ResolveContentBinding(
                contentBindingRepository = bindings,
                canonicalTitleRepository = titleRepository,
                addonRepository = addons,
                readingSourceGateway = harness.gateway,
                scoreSourceTitleMatch = ScoreSourceTitleMatch(),
                addonSourceEligibilityRepository = AddonSourceEligibilityRepository { emptyList() },
                diagnostics = diagnostics,
            )
            refresh = RefreshChapterEvidence(
                registry = EmptyIntegrationRegistry,
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = parser,
                    canonicalChapterRepository = canonicalChapters,
                    evidenceRepository = evidence,
                ),
                addonRegistry = registry,
                resolveContentBinding = bindingResolver,
                contentOptionCache = ContentOptionCache(),
                diagnostics = diagnostics,
            )
            selector = ResolveChapterContent(
                addonRegistry = registry,
                contentPreferenceRepository = NoContentPreferences,
                readerPreferences = tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences(
                    InMemoryPreferenceStore(),
                ),
                rankContentOptions = RankContentOptions(),
                contentOptionCache = ContentOptionCache(),
                inFlightContentResolution = InFlightContentResolution(),
                addonRepository = addons,
                diagnostics = diagnostics,
            )
        }

        private fun everyInsertAndReadManga() {
            coEvery { harness.mangaRepository.insertNetworkManga(any()) } coAnswers {
                firstArg<List<Manga>>().map { manga ->
                    val id = nextMangaId.getAndIncrement()
                    manga.copy(id = id).also { persistedManga[id] = it }
                }
            }
            coEvery { harness.mangaRepository.getMangaById(any()) } coAnswers {
                persistedManga.getValue(firstArg<Long>())
            }
        }

        suspend fun bind() = bindingResolver.executeAll(canonicalTitleId, addonId).getOrThrow()
        suspend fun bindResult() = bindingResolver.executeAll(canonicalTitleId, addonId)
        suspend fun refresh() = refresh.execute(canonicalTitleId).getOrThrow()
        suspend fun installedSources() = harness.gateway.listInstalled("en")
        suspend fun options(chapterId: String) = selector.lookupOptions(canonicalTitleId, chapterId).options
    }

    private class RecordingDiagnostics(private val titleId: String) : ChapterInventoryDiagnostics {
        val events = mutableListOf<ChapterInventoryDiagnosticEvent>()
        private var recording = false

        override fun start(canonicalTitleId: String): String {
            recording = canonicalTitleId == titleId
            return "test-session"
        }

        override fun stop() {
            recording = false
        }

        override fun clear() {
            events.clear()
            recording = false
        }

        override fun isRecording(canonicalTitleId: String): Boolean = recording && canonicalTitleId == titleId

        override fun record(event: ChapterInventoryDiagnosticEvent) {
            events += event
        }

        override fun report(): String = ""
    }

    private class SingleAddonRepository(private val addon: InstalledAddon) : AddonRepository {
        override fun observeInstalled(): Flow<List<InstalledAddon>> = MutableStateFlow(listOf(addon))
        override suspend fun snapshot(): List<InstalledAddon> = listOf(addon)
        override suspend fun setEnabled(id: AddonId, enabled: Boolean) = Unit
    }

    private class InMemoryContentBindings : ContentBindingRepository {
        private val values = linkedMapOf<String, ContentBinding>()
        override suspend fun get(canonicalTitleId: String, addonId: AddonId): ContentBinding? =
            values.values.firstOrNull { it.canonicalTitleId == canonicalTitleId && it.addonId == addonId }
        override suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding> =
            values.values.filter { it.canonicalTitleId == canonicalTitleId }
        override suspend fun upsert(binding: ContentBinding) {
            values[binding.id] = binding
        }
        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
            values.replaceAll { _, binding ->
                if (binding.id == bindingId) {
                    binding.copy(availability = ContentBindingAvailability.UNAVAILABLE)
                } else {
                    binding
                }
            }
        }
    }

    private class InMemoryChapterEvidence : ChapterEvidenceRepository {
        private val values = linkedMapOf<String, PersistedChapterEvidence>()
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<PersistedChapterEvidence> =
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
        ): PersistedChapterEvidence = PersistedChapterEvidence(evidence, mappedCanonicalChapterId)
            .also { values[evidence.id] = it }
    }

    private class InMemoryCanonicalChapters : CanonicalChapterRepository {
        private val chapters = linkedMapOf<String, CanonicalChapter>()
        private val variants = linkedMapOf<String, ChapterVariant>()
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
            chapters.forEach { upsert(it) }
            variants.forEach { upsertVariant(it) }
        }
    }

    private class InMemoryMihonChapters {
        private val values = mutableListOf<Chapter>()
        private val nextId = AtomicLong(100L)
        val repository: ChapterRepository = mockk(relaxed = true) {
            coEvery { getChapterByMangaId(any()) } coAnswers {
                values.filter { it.mangaId == firstArg<Long>() }
            }
            coEvery { getChapterById(any()) } coAnswers {
                values.firstOrNull { it.id == firstArg<Long>() }
            }
            coEvery { getChapterByUrlAndMangaId(any(), any()) } coAnswers {
                val url = firstArg<String>()
                val mangaId = secondArg<Long>()
                values.firstOrNull { it.url == url && it.mangaId == mangaId }
            }
            coEvery { addAll(any()) } coAnswers {
                firstArg<List<Chapter>>().map { chapter ->
                    chapter.copy(id = nextId.getAndIncrement()).also(values::add)
                }
            }
        }
        fun onlyRow(): Chapter = values.single()
    }

    private object NoContentPreferences : ContentPreferenceRepository {
        override suspend fun get(canonicalTitleId: String): ContentPreference? = null
        override fun observe(canonicalTitleId: String): Flow<ContentPreference?> = MutableStateFlow(null)
        override suspend fun upsert(preference: ContentPreference) = error("Preference must not be written")
        override suspend fun delete(canonicalTitleId: String) = error("Preference must not be deleted")
    }

    private object EmptyIntegrationRegistry : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = emptyList()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }
}
