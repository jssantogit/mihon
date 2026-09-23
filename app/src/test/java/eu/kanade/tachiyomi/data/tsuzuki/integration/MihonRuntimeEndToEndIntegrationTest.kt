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
import tachiyomi.domain.tsuzuki.chapter.diagnostics.NoOpChapterInventoryDiagnostics
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
            val addonId = AddonId("fixture-addon")
            val canonicalTitleId = "canonical-opm"
            val sourceUrl = "/manga/one-punch-man"
            val chapterUrl = "/chapter/1"
            harness.enqueue(body = "$sourceUrl\tOne-Punch Man")
            harness.enqueue(body = "$chapterUrl\tChapter 1\t1\tFixture Group")

            val localMangaId = 9001L
            val persistedManga = mutableMapOf<Long, Manga>()
            coEvery { harness.mangaRepository.insertNetworkManga(any()) } coAnswers {
                firstArg<List<Manga>>().map { manga ->
                    manga.copy(id = localMangaId).also { persistedManga[localMangaId] = it }
                }
            }
            coEvery { harness.mangaRepository.getMangaById(localMangaId) } coAnswers {
                persistedManga.getValue(localMangaId)
            }

            val chapterRows = InMemoryMihonChapters()
            val bindings = InMemoryContentBindings()
            val canonicalChapters = InMemoryCanonicalChapters()
            val evidence = InMemoryChapterEvidence()
            val addon = InstalledAddon(
                id = addonId,
                displayName = "Fixture Add-on",
                enabled = true,
                versionName = "test",
                mihonSourceIds = listOf(harness.source.id),
                hasSettings = false,
            )
            val addons = SingleAddonRepository(addon)
            val titleRepository = mockk<CanonicalTitleRepository> {
                coEvery { getById(canonicalTitleId) } returns CanonicalTitle(
                    id = canonicalTitleId,
                    displayTitle = "One-Punch Man",
                    identityState = CanonicalIdentityState.RESOLVED,
                    createdAt = 1L,
                    updatedAt = 1L,
                )
            }
            val installedSources = harness.gateway.listInstalled("en")
            installedSources.map { it.sourceId } shouldBe listOf(harness.source.id)

            val bindingResolver = ResolveContentBinding(
                contentBindingRepository = bindings,
                canonicalTitleRepository = titleRepository,
                addonRepository = addons,
                readingSourceGateway = harness.gateway,
                scoreSourceTitleMatch = ScoreSourceTitleMatch(),
                addonSourceEligibilityRepository = AddonSourceEligibilityRepository { emptyList() },
                diagnostics = NoOpChapterInventoryDiagnostics,
            )
            val chapterGateway = MihonChapterInventoryGateway(
                mangaRepository = harness.mangaRepository,
                chapterRepository = chapterRows.repository,
                sourceManager = harness.sourceManager,
            )
            val parser = ParseCanonicalChapterLabel()
            val providerFactory = MihonAddonProviderFactory(
                contentBindingRepository = bindings,
                canonicalChapterRepository = canonicalChapters,
                chapterEvidenceRepository = evidence,
                parser = parser,
                chapterInventoryGateway = chapterGateway,
                chapterInventoryDiagnostics = NoOpChapterInventoryDiagnostics,
            )
            val provider = providerFactory.contentProvider(addonId)
            val registry = DefaultAddonRegistry(
                installedAddons = { listOf(addon) },
                contentProviderCandidates = listOf(provider),
                chapterProbeProviderCandidates = listOf(providerFactory.chapterProbeProvider(addonId)),
            )
            val refresh = RefreshChapterEvidence(
                registry = EmptyIntegrationRegistry,
                reconcileChapterEvidence = ReconcileChapterEvidence(
                    parser = parser,
                    canonicalChapterRepository = canonicalChapters,
                    evidenceRepository = evidence,
                ),
                addonRegistry = registry,
                resolveContentBinding = bindingResolver,
                contentOptionCache = ContentOptionCache(),
                diagnostics = NoOpChapterInventoryDiagnostics,
            )

            val binding = bindingResolver.executeAll(canonicalTitleId, addonId).getOrThrow().single()
            binding.canonicalTitleId shouldBe canonicalTitleId
            binding.availability shouldBe ContentBindingAvailability.AVAILABLE
            bindings.getByTitle(canonicalTitleId).single() shouldBe binding
            MihonContentBindingPayloadCodec.decode(binding.runtimePayload).mihonMangaId shouldBe localMangaId

            refresh.execute(canonicalTitleId).getOrThrow()

            val reconciled = canonicalChapters.getByCanonicalTitleId(canonicalTitleId).single()
            reconciled.displayNumber shouldBe "1"
            reconciled.confirmation shouldBe CanonicalChapterConfirmation.PROVISIONAL
            evidence.getByCanonicalTitleId(canonicalTitleId).single().mappedCanonicalChapterId shouldBe reconciled.id

            val selector = ResolveChapterContent(
                addonRegistry = registry,
                contentPreferenceRepository = NoContentPreferences,
                readerPreferences = tachiyomi.domain.tsuzuki.reader.model.CanonicalReaderPreferences(
                    InMemoryPreferenceStore(),
                ),
                rankContentOptions =
                    RankContentOptions(),
                contentOptionCache = ContentOptionCache(),
                inFlightContentResolution = InFlightContentResolution(),
                addonRepository = addons,
            )
            val selectedChapterOptions = selector.lookupOptions(canonicalTitleId, reconciled.id).options

            selectedChapterOptions.size shouldBe 1
            selectedChapterOptions.single().canonicalChapterId shouldBe reconciled.id
            selectedChapterOptions.single().addonId shouldBe addonId
            selectedChapterOptions.single().language shouldBe "en"
            selectedChapterOptions.single().delivery shouldBe ContentDelivery.Mihon(
                sourceId = harness.source.id,
                mangaId = localMangaId,
                chapterId = chapterRows.onlyRow().id,
            )
            harness.server.requestCount shouldBe 2
        }
    }

    private class SingleAddonRepository(private val addon: InstalledAddon) : AddonRepository {
        override fun observeInstalled(): Flow<List<InstalledAddon>> = MutableStateFlow(listOf(addon))
        override suspend fun snapshot(): List<InstalledAddon> = listOf(addon)
        override suspend fun setEnabled(id: AddonId, enabled: Boolean) = Unit
    }

    private class InMemoryContentBindings : ContentBindingRepository {
        private val values = linkedMapOf<Pair<String, AddonId>, ContentBinding>()
        override suspend fun get(canonicalTitleId: String, addonId: AddonId): ContentBinding? =
            values[canonicalTitleId to addonId]
        override suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding> =
            values.values.filter { it.canonicalTitleId == canonicalTitleId }
        override suspend fun upsert(binding: ContentBinding) {
            values[binding.canonicalTitleId to binding.addonId] = binding
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
