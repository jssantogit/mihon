package eu.kanade.tachiyomi.data.tsuzuki.addon

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.AddonRegistry
import tachiyomi.domain.tsuzuki.addon.ChapterProbeProvider
import tachiyomi.domain.tsuzuki.addon.ContentProvider
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
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
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterInventory
import tachiyomi.domain.tsuzuki.chapter.model.SourceChapterSnapshot
import tachiyomi.domain.tsuzuki.chapter.repository.CanonicalChapterRepository
import tachiyomi.domain.tsuzuki.content.ContentBinding
import tachiyomi.domain.tsuzuki.content.ContentBindingAvailability
import tachiyomi.domain.tsuzuki.content.ContentDelivery
import tachiyomi.domain.tsuzuki.content.cache.ContentOptionCache
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.integration.ChapterEvidenceProvider
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.TrackingProvider
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

// Guards the human smoke path from canonical catalog title through Add-on content.
class RuntimeV2SmokeReadinessTest {

    @Test
    fun `multi-source Add-on provisions canonical chapters and content options`() = runTest {
        val addonId = AddonId("mangadex")
        val bindings = FakeContentBindingRepository()
        val chapters = FakeCanonicalChapterRepository()
        val evidence = FakeChapterEvidenceRepository()
        val addonRepository = FakeAddonRepository(addonId)
        val sourceGateway = FakeReadingSourceGateway()
        val bindingResolver = ResolveContentBinding(
            contentBindingRepository = bindings,
            canonicalTitleRepository = FakeCanonicalTitleRepository(),
            addonRepository = addonRepository,
            readingSourceGateway = sourceGateway,
            scoreSourceTitleMatch = ScoreSourceTitleMatch(),
        )
        val parser = ParseCanonicalChapterLabel()
        val probe = MihonChapterProbeProvider(
            addonId = addonId,
            contentBindingRepository = bindings,
            parser = parser,
            fetchInventory = ::inventoryFor,
        )
        val addonRegistry = object : AddonRegistry {
            override fun contentProviders(): List<ContentProvider> = emptyList()
            override fun chapterProbeProviders(): List<ChapterProbeProvider> = listOf(probe)
        }
        val refresh = RefreshChapterEvidence(
            registry = emptyIntegrationRegistry(),
            reconcileChapterEvidence = ReconcileChapterEvidence(
                parser = parser,
                canonicalChapterRepository = chapters,
                evidenceRepository = evidence,
            ),
            addonRegistry = addonRegistry,
            resolveContentBinding = bindingResolver,
            contentOptionCache = ContentOptionCache(),
        )

        refresh.execute(TITLE_ID).isSuccess shouldBe true

        val canonicalChapters = chapters.getByCanonicalTitleId(TITLE_ID)
        canonicalChapters.size shouldBe 1
        val canonicalChapter = canonicalChapters.single()
        canonicalChapter.displayNumber shouldBe "37"
        canonicalChapter.confirmation shouldBe CanonicalChapterConfirmation.PROVISIONAL
        bindings.getByTitle(TITLE_ID).size shouldBe 2

        val contentProvider = MihonContentProvider(
            addonId = addonId,
            contentBindingRepository = bindings,
            canonicalChapterRepository = chapters,
            parser = parser,
            fetchInventory = ::inventoryFor,
            materializeDelivery = { binding, snapshot ->
                Result.success(
                    ContentDelivery.Mihon(
                        sourceId = snapshot.sourceId,
                        mangaId = snapshot.mihonMangaId ?: error("missing manga"),
                        chapterId = if (binding.providerTitleKey.startsWith("7:")) 700L else 800L,
                    ),
                )
            },
            chapterEvidenceRepository = evidence,
        )

        val options = contentProvider.resolve(TITLE_ID, canonicalChapter.id).getOrThrow()

        options.size shouldBe 2
        options.mapNotNull { it.language }.shouldContainExactlyInAnyOrder("en", "pt-BR")
        options.map { it.addonId }.distinct() shouldBe listOf(addonId)
    }

    private fun inventoryFor(binding: ContentBinding): Result<SourceChapterInventory> {
        val sourceId = binding.providerTitleKey.substringBefore(':').toLong()
        val language = if (sourceId == 7L) "en" else "pt-BR"
        val mangaId = if (sourceId == 7L) 70L else 80L
        return Result.success(
            SourceChapterInventory(
                sourceMappingId = binding.id,
                sourceId = sourceId,
                canonicalTitleId = TITLE_ID,
                chapters = listOf(
                    SourceChapterSnapshot(
                        sourceId = sourceId,
                        sourceMappingId = binding.id,
                        sourceChapterId = "/chapter-37",
                        sourceChapterUrl = "/chapter-37",
                        rawName = "Chapter 37",
                        language = language,
                        scanlationGroup = if (sourceId == 7L) "Group EN" else "Grupo PT",
                        releaseDate = 37L,
                        rawNumberHint = 37.0,
                        mihonMangaId = mangaId,
                    ),
                ),
                mihonMangaId = mangaId,
                language = language,
            ),
        )
    }

    private class FakeAddonRepository(
        private val addonId: AddonId,
    ) : AddonRepository {
        private val addon = InstalledAddon(
            id = addonId,
            displayName = "MangaDex",
            enabled = true,
            versionName = "1.0",
            mihonSourceIds = listOf(7L, 8L),
            hasSettings = false,
        )

        override fun observeInstalled(): Flow<List<InstalledAddon>> = MutableStateFlow(listOf(addon))
        override suspend fun snapshot(): List<InstalledAddon> = listOf(addon)
        override suspend fun setEnabled(id: AddonId, enabled: Boolean) = Unit
    }

    private class FakeReadingSourceGateway : ReadingSourceGateway {
        override suspend fun listInstalled(language: String): List<ReadingSourceDescriptor> = emptyList()

        override suspend fun search(
            sourceId: Long,
            query: String,
        ): Result<List<ReadingSourceCandidate>> {
            val language = if (sourceId == 7L) "en" else "pt-BR"
            return Result.success(
                listOf(
                    ReadingSourceCandidate(
                        sourceId = sourceId,
                        sourceName = "MangaDex $language",
                        language = language,
                        sourceUrl = "/$language/dandadan",
                        title = "Dandadan",
                        thumbnailUrl = null,
                        author = null,
                        artist = null,
                        description = null,
                        genres = null,
                        status = 0L,
                    ),
                ),
            )
        }

        override suspend fun materialize(
            candidate: ReadingSourceCandidate,
        ): Result<MaterializedReadingSource> = Result.success(
            MaterializedReadingSource(
                mihonMangaId = if (candidate.sourceId == 7L) 70L else 80L,
                sourceId = candidate.sourceId,
                sourceUrl = candidate.sourceUrl,
                language = candidate.language,
                runtimePayload = byteArrayOf(candidate.sourceId.toByte()),
            ),
        )
    }

    private class FakeContentBindingRepository : ContentBindingRepository {
        private val bindings = mutableListOf<ContentBinding>()

        override suspend fun get(
            canonicalTitleId: String,
            addonId: AddonId,
        ): ContentBinding? = bindings.lastOrNull {
            it.canonicalTitleId == canonicalTitleId && it.addonId == addonId
        }

        override suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding> =
            bindings.filter { it.canonicalTitleId == canonicalTitleId }

        override suspend fun upsert(binding: ContentBinding) {
            bindings.removeAll { it.id == binding.id }
            bindings += binding
        }

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
            val index = bindings.indexOfFirst { it.id == bindingId }
            if (index >= 0) {
                bindings[index] = bindings[index].copy(
                    availability = ContentBindingAvailability.UNAVAILABLE,
                    updatedAt = updatedAt,
                )
            }
        }
    }

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        private val title = CanonicalTitle(
            id = TITLE_ID,
            displayTitle = "Dandadan",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1L,
            updatedAt = 1L,
        )

        override suspend fun getById(id: String): CanonicalTitle? = title.takeIf { it.id == id }
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> =
            MutableStateFlow(title.takeIf { it.id == id })
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = title
        override suspend fun insert(title: CanonicalTitle) = Unit
        override suspend fun addExternalIdentity(identity: ExternalIdentity) = Unit
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
            val persisted = PersistedChapterEvidence(evidence, mappedCanonicalChapterId)
            val index = records.indexOfFirst {
                it.evidence.producerKind == evidence.producerKind &&
                    it.evidence.producerId == evidence.producerId &&
                    it.evidence.externalChapterKey == evidence.externalChapterKey
            }
            if (index >= 0) records[index] = persisted else records += persisted
            return persisted
        }
    }

    private class FakeCanonicalChapterRepository : CanonicalChapterRepository {
        private val chapters = linkedMapOf<String, CanonicalChapter>()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<CanonicalChapter> =
            chapters.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun observeByCanonicalTitleId(canonicalTitleId: String): Flow<List<CanonicalChapter>> =
            MutableStateFlow(chapters.values.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getById(id: String): CanonicalChapter? = chapters[id]
        override suspend fun getVariantBySourceIdentity(sourceId: Long, sourceChapterId: String): ChapterVariant? = null
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

    private fun emptyIntegrationRegistry() = object : IntegrationRegistry {
        override fun searchProviders(): List<SearchProvider> = emptyList()
        override fun discoveryProviders(): List<DiscoveryProvider> = emptyList()
        override fun metadataProviders(): List<MetadataProvider> = emptyList()
        override fun chapterEvidenceProviders(): List<ChapterEvidenceProvider> = emptyList()
        override fun ratingsProviders(): List<RatingsProvider> = emptyList()
        override fun trackingProviders(): List<TrackingProvider> = emptyList()
    }

    private companion object {
        const val TITLE_ID = "title-dandadan"
    }
}
