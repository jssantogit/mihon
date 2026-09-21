package tachiyomi.domain.tsuzuki.content

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.addon.AddonId
import tachiyomi.domain.tsuzuki.addon.model.InstalledAddon
import tachiyomi.domain.tsuzuki.addon.repository.AddonRepository
import tachiyomi.domain.tsuzuki.content.interactor.ResolveContentBinding
import tachiyomi.domain.tsuzuki.content.repository.ContentBindingRepository
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

class ResolveContentBindingTest {

    @Test
    fun `existing addon binding is reused without title search`() = runTest {
        val existing = binding(
            providerTitleKey = "remote-123",
            availability = ContentBindingAvailability.AVAILABLE,
        )
        val repository = FakeContentBindingRepository(existing)
        val gateway = FakeReadingSourceGateway()
        val resolver = resolver(repository, gateway)

        val result = resolver.execute("title", AddonId("mangadex")).getOrThrow()

        result.providerTitleKey shouldBe "remote-123"
        gateway.searchCalls shouldBe 0
    }

    @Test
    fun `stale binding is repaired inside same canonical title`() = runTest {
        val repository = FakeContentBindingRepository(
            binding(
                providerTitleKey = "old",
                availability = ContentBindingAvailability.UNAVAILABLE,
            ),
        )
        val gateway = FakeReadingSourceGateway(
            searchResults = mapOf(
                7L to listOf(candidate(sourceId = 7L, sourceUrl = "/new", title = "Dandadan")),
            ),
            materialized = MaterializedReadingSource(
                mihonMangaId = 99L,
                sourceId = 7L,
                sourceUrl = "/new",
                language = "en",
            ),
        )
        val resolver = resolver(repository, gateway)

        val repaired = resolver.execute("title", AddonId("mangadex")).getOrThrow()

        repaired.id shouldBe "binding"
        repaired.canonicalTitleId shouldBe "title"
        repaired.providerTitleKey shouldBe "7:/new"
        repaired.availability shouldBe ContentBindingAvailability.AVAILABLE
        repaired.runtimePayload.isNotEmpty() shouldBe true
        gateway.searchCalls shouldBe 1
        gateway.materializeCalls shouldBe 1
    }

    private fun resolver(
        repository: FakeContentBindingRepository,
        gateway: FakeReadingSourceGateway,
    ) = ResolveContentBinding(
        contentBindingRepository = repository,
        canonicalTitleRepository = FakeCanonicalTitleRepository(),
        addonRepository = FakeAddonRepository(),
        readingSourceGateway = gateway,
        scoreSourceTitleMatch = ScoreSourceTitleMatch(),
        idFactory = { "new-binding" },
        clock = { 200L },
    )

    private fun binding(
        providerTitleKey: String,
        availability: ContentBindingAvailability,
    ) = ContentBinding(
        id = "binding",
        canonicalTitleId = "title",
        addonId = AddonId("mangadex"),
        providerTitleKey = providerTitleKey,
        matchConfidence = 1.0,
        verifiedByUser = false,
        availability = availability,
        runtimePayload = byteArrayOf(1),
        createdAt = 100L,
        updatedAt = 100L,
    )

    private fun candidate(
        sourceId: Long,
        sourceUrl: String,
        title: String,
    ) = ReadingSourceCandidate(
        sourceId = sourceId,
        sourceName = "Source $sourceId",
        language = "en",
        sourceUrl = sourceUrl,
        title = title,
        thumbnailUrl = null,
        author = null,
        artist = null,
        description = null,
        genres = null,
        status = 0L,
    )

    private class FakeContentBindingRepository(
        initial: ContentBinding?,
    ) : ContentBindingRepository {
        private var value = initial

        override suspend fun get(canonicalTitleId: String, addonId: AddonId): ContentBinding? {
            return value?.takeIf { it.canonicalTitleId == canonicalTitleId && it.addonId == addonId }
        }

        override suspend fun getByTitle(canonicalTitleId: String): List<ContentBinding> {
            return listOfNotNull(value?.takeIf { it.canonicalTitleId == canonicalTitleId })
        }

        override suspend fun upsert(binding: ContentBinding) {
            value = binding
        }

        override suspend fun markUnavailable(bindingId: String, updatedAt: Long) {
            value = value?.takeIf { it.id == bindingId }?.copy(
                availability = ContentBindingAvailability.UNAVAILABLE,
                updatedAt = updatedAt,
            )
        }
    }

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        private val title = CanonicalTitle(
            id = "title",
            displayTitle = "Dandadan",
            identityState = CanonicalIdentityState.SOURCE_ONLY,
            createdAt = 1L,
            updatedAt = 1L,
        )

        override suspend fun getById(id: String): CanonicalTitle? = title.takeIf { it.id == id }
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = flowOf(title.takeIf { it.id == id })
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null

        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: tachiyomi.domain.tsuzuki.model.ExternalIdentity,
        ): CanonicalTitle = error("unused")

        override suspend fun insert(title: CanonicalTitle) = error("unused")
        override suspend fun addExternalIdentity(identity: tachiyomi.domain.tsuzuki.model.ExternalIdentity) =
            error("unused")
    }

    private class FakeAddonRepository : AddonRepository {
        private val addon = InstalledAddon(
            id = AddonId("mangadex"),
            displayName = "MangaDex",
            enabled = true,
            versionName = "1.0",
            mihonSourceIds = listOf(7L),
            hasSettings = false,
        )

        override fun observeInstalled() = flowOf(listOf(addon))
        override suspend fun snapshot() = listOf(addon)
        override suspend fun setEnabled(id: AddonId, enabled: Boolean) = Unit
    }

    private class FakeReadingSourceGateway(
        private val searchResults: Map<Long, List<ReadingSourceCandidate>> = emptyMap(),
        private val materialized: MaterializedReadingSource? = null,
    ) : ReadingSourceGateway {
        var searchCalls = 0
        var materializeCalls = 0

        override suspend fun listInstalled(language: String): List<ReadingSourceDescriptor> = emptyList()

        override suspend fun search(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>> {
            searchCalls += 1
            return Result.success(searchResults[sourceId].orEmpty())
        }

        override suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource> {
            materializeCalls += 1
            return materialized?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("No materialized source configured"))
        }
    }
}
