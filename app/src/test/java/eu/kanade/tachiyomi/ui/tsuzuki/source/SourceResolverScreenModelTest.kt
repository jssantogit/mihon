package eu.kanade.tachiyomi.ui.tsuzuki.source

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.ConfirmSourceMapping
import tachiyomi.domain.tsuzuki.source.interactor.GetPreferredReadingSources
import tachiyomi.domain.tsuzuki.source.interactor.ResolveReadingSource
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import tachiyomi.domain.tsuzuki.source.interactor.SetTitleSourceOverride
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

@OptIn(ExperimentalCoroutinesApi::class)
class SourceResolverScreenModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `persisted mapping is reused before searching`() = runTest(dispatcher) {
        val mapping = mapping("m-1", "title-1", 10L, "/frieren", preferred = true)
        val mappings = FakeMappings().apply { upsert(mapping) }
        val gateway = FakeGateway()
        val model = model(mappings = mappings, gateway = gateway)

        model.start("title-1", "Frieren")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<SourceResolverScreenState.Resolved>()
        state.mapping shouldBe mapping
        state.reused shouldBe true
        gateway.searches shouldBe emptyList()
    }

    @Test
    fun `incomplete persisted mapping is repaired before showing resolved`() = runTest(dispatcher) {
        val mapping = mapping(
            id = "m-1",
            titleId = "title-1",
            sourceId = 10L,
            url = "/frieren",
            preferred = true,
            mihonMangaId = null,
            availability = SourceMappingAvailability.UNKNOWN,
        )
        val mappings = FakeMappings().apply { upsert(mapping) }
        val gateway = FakeGateway()
        val model = model(mappings = mappings, gateway = gateway)

        model.start("title-1", "Frieren")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<SourceResolverScreenState.Resolved>()
        state.reused shouldBe true
        state.mapping.id shouldBe mapping.id
        state.mapping.mihonMangaId shouldBe 1010L
        state.mapping.availability shouldBe SourceMappingAvailability.AVAILABLE
        gateway.searches shouldBe emptyList()
    }

    @Test
    fun `no configured sources is exposed as no preferred sources`() = runTest(dispatcher) {
        val model = model()

        model.start("title-1", "Frieren")
        advanceUntilIdle()

        model.state.value shouldBe SourceResolverScreenState.NoPreferredSources(
            title = "Frieren",
            language = "",
        )
    }

    @Test
    fun `one language searches and resolves an exact candidate`() = runTest(dispatcher) {
        val preferences = FakePreferences().apply { replaceForLanguage("en", listOf(10L)) }
        val candidate = candidate(10L, "Frieren", "/frieren")
        val gateway = FakeGateway(searchResults = mapOf(10L to Result.success(listOf(candidate))))
        val model = model(preferences = preferences, gateway = gateway)

        model.start("title-1", "Frieren")
        model.state.value shouldBe SourceResolverScreenState.Loading
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<SourceResolverScreenState.Resolved>()
        state.mapping.sourceId shouldBe 10L
        state.reused shouldBe false
        gateway.searches shouldBe listOf(10L)
    }

    @Test
    fun `multiple languages require explicit language selection`() = runTest(dispatcher) {
        val preferences = FakePreferences().apply {
            replaceForLanguage("en", listOf(10L))
            replaceForLanguage("ja", listOf(20L))
        }
        val model = model(preferences = preferences)

        model.start("title-1", "Frieren")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<SourceResolverScreenState.SelectLanguage>()
        state.languages shouldBe listOf("en", "ja")

        model.selectLanguage("ja")
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<SourceResolverScreenState.NotFound>().language shouldBe "ja"
    }

    @Test
    fun `manual confirmation persists an ambiguous candidate`() = runTest(dispatcher) {
        val preferences = FakePreferences().apply { replaceForLanguage("en", listOf(10L)) }
        val first = candidate(10L, "Frieren", "/a")
        val second = candidate(10L, "Frieren!", "/b")
        val gateway = FakeGateway(searchResults = mapOf(10L to Result.success(listOf(first, second))))
        val model = model(preferences = preferences, gateway = gateway)

        model.start("title-1", "Frieren")
        advanceUntilIdle()

        val confirmation = model.state.value.shouldBeInstanceOf<SourceResolverScreenState.NeedsConfirmation>()
        confirmation.candidates.size shouldBe 2
        model.confirm(confirmation.candidates.first())
        advanceUntilIdle()

        val resolved = model.state.value.shouldBeInstanceOf<SourceResolverScreenState.Resolved>()
        resolved.mapping.verifiedByUser shouldBe true
        resolved.reused shouldBe false
    }

    @Test
    fun `not found exposes explicit broaden action`() = runTest(dispatcher) {
        val preferences = FakePreferences().apply { replaceForLanguage("en", listOf(10L)) }
        val candidate = candidate(20L, "Frieren", "/extra")
        val gateway = FakeGateway(
            installed = listOf(descriptor(10L, "Preferred", "en"), descriptor(20L, "Extra", "en")),
            searchResults = mapOf(
                10L to Result.success(emptyList()),
                20L to Result.success(listOf(candidate)),
            ),
        )
        val model = model(preferences = preferences, gateway = gateway)

        model.start("title-1", "Frieren")
        advanceUntilIdle()
        val notFound = model.state.value.shouldBeInstanceOf<SourceResolverScreenState.NotFound>()
        notFound.canBroaden shouldBe true
        gateway.searches shouldBe listOf(10L)

        model.checkMoreSources()
        advanceUntilIdle()
        model.state.value.shouldBeInstanceOf<SourceResolverScreenState.Resolved>().mapping.sourceId shouldBe 20L
        gateway.searches shouldContain 20L
    }

    @Test
    fun `conflicting mapping is surfaced and not reassigned`() = runTest(dispatcher) {
        val preferences = FakePreferences().apply { replaceForLanguage("en", listOf(10L)) }
        val existing = mapping("other", "other-title", 10L, "/same")
        val mappings = FakeMappings().apply { upsert(existing) }
        val gateway = FakeGateway(
            searchResults = mapOf(
                10L to Result.success(listOf(candidate(10L, "Frieren", "/same"))),
            ),
        )
        val model = model(preferences = preferences, mappings = mappings, gateway = gateway)

        model.start("title-1", "Frieren")
        advanceUntilIdle()

        model.state.value shouldBe SourceResolverScreenState.Conflict(
            title = "Frieren",
            existingCanonicalTitleId = "other-title",
        )
        mappings.getByCanonicalTitleId("title-1") shouldBe emptyList()
    }

    @Test
    fun `title override changes preferred mapping without deleting alternatives`() = runTest(dispatcher) {
        val first = mapping("m-1", "title-1", 10L, "/a")
        val second = mapping("m-2", "title-1", 20L, "/b")
        val mappings = FakeMappings().apply {
            upsert(first)
            upsert(second)
        }
        val model = model(mappings = mappings)

        model.start("title-1", "Frieren")
        advanceUntilIdle()
        model.setTitleSourceOverride("m-2")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<SourceResolverScreenState.Resolved>()
        state.mapping.id shouldBe "m-2"
        state.mappings.map { it.id } shouldBe listOf("m-1", "m-2")
        state.mappings.count { it.preferredOverride } shouldBe 1
    }

    @Test
    fun `source failure leaves canonical mapping state intact`() = runTest(dispatcher) {
        val preferences = FakePreferences().apply { replaceForLanguage("en", listOf(10L)) }
        val mappings = FakeMappings()
        val gateway = FakeGateway(searchResults = mapOf(10L to Result.failure(IllegalStateException("offline"))))
        val model = model(preferences = preferences, mappings = mappings, gateway = gateway)

        model.start("title-1", "Frieren")
        advanceUntilIdle()

        model.state.value.shouldBeInstanceOf<SourceResolverScreenState.NotFound>()
        mappings.getByCanonicalTitleId("title-1") shouldBe emptyList()
    }

    @Test
    fun `unexpected application failure maps to error`() = runTest(dispatcher) {
        val preferences = FakePreferences(throwOnLanguages = true)
        val model = model(preferences = preferences)

        model.start("title-1", "Frieren")
        advanceUntilIdle()

        val state = model.state.value.shouldBeInstanceOf<SourceResolverScreenState.Error>()
        state.title shouldBe "Frieren"
        state.error.message shouldBe "preference failure"
    }

    private fun model(
        preferences: FakePreferences = FakePreferences(),
        mappings: FakeMappings = FakeMappings(),
        gateway: FakeGateway = FakeGateway(),
    ) = SourceResolverScreenModel(
        getPreferredReadingSources = GetPreferredReadingSources(preferences),
        resolveReadingSource = ResolveReadingSource(
            canonicalTitleRepository = FakeCanonicalTitles(),
            sourceTitleMappingRepository = mappings,
            getPreferredReadingSources = GetPreferredReadingSources(preferences),
            readingSourceGateway = gateway,
            scoreSourceTitleMatch = ScoreSourceTitleMatch(),
            confirmSourceMapping = ConfirmSourceMapping(mappings, gateway),
        ),
        confirmSourceMapping = ConfirmSourceMapping(mappings, gateway),
        setTitleSourceOverride = SetTitleSourceOverride(mappings),
        sourceTitleMappingRepository = mappings,
    )

    private fun descriptor(id: Long, name: String, language: String) = ReadingSourceDescriptor(id, name, language)

    private fun candidate(sourceId: Long, title: String, url: String) = ReadingSourceCandidate(
        sourceId = sourceId,
        sourceName = "Source $sourceId",
        language = "en",
        sourceUrl = url,
        title = title,
        thumbnailUrl = "https://example.test/thumb.jpg",
        author = "Author",
        artist = null,
        description = null,
        genres = null,
        status = 0L,
    )

    private fun mapping(
        id: String,
        titleId: String,
        sourceId: Long,
        url: String,
        preferred: Boolean = false,
        mihonMangaId: Long? = sourceId,
        availability: SourceMappingAvailability = SourceMappingAvailability.AVAILABLE,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = titleId,
        mihonMangaId = mihonMangaId,
        sourceId = sourceId,
        sourceUrl = url,
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = false,
        availability = availability,
        preferredOverride = preferred,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private class FakePreferences(
        private val throwOnLanguages: Boolean = false,
    ) : ReadingSourcePreferenceRepository {
        private val values = mutableMapOf<String, List<ReadingSourcePreference>>()
        private val flows = mutableMapOf<String, MutableStateFlow<List<ReadingSourcePreference>>>()

        override suspend fun getForLanguage(
            language: String,
        ): List<ReadingSourcePreference> = values[language].orEmpty()

        override fun observeForLanguage(
            language: String,
        ): Flow<List<ReadingSourcePreference>> =
            flows.getOrPut(language) { MutableStateFlow(values[language].orEmpty()) }

        override suspend fun getConfiguredLanguages(): List<String> {
            if (throwOnLanguages) error("preference failure")
            return values.filterValues { it.isNotEmpty() }.keys.sorted()
        }

        override suspend fun replaceForLanguage(language: String, orderedSourceIds: List<Long>) {
            val next = orderedSourceIds.mapIndexed { index, id -> ReadingSourcePreference(language, id, index) }
            values[language] = next
            flows.getOrPut(language) { MutableStateFlow(emptyList()) }.value = next
        }
    }

    private class FakeMappings : SourceTitleMappingRepository {
        private val values = linkedMapOf<String, SourceTitleMapping>()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            values.values.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(values.values.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            values.values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) {
            values[mapping.id] = mapping
        }

        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) {
            if (mappingId != null) {
                val mapping = values[mappingId] ?: error("Mapping not found")
                require(mapping.canonicalTitleId == canonicalTitleId)
            }
            values.entries
                .filter { it.value.canonicalTitleId == canonicalTitleId }
                .forEach { (id, value) ->
                    values[id] = value.copy(preferredOverride = id == mappingId, updatedAt = updatedAt)
                }
        }
    }

    private class FakeCanonicalTitles : CanonicalTitleRepository {
        override suspend fun getById(id: String): CanonicalTitle = CanonicalTitle(
            id = id,
            displayTitle = "Frieren",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1L,
            updatedAt = 1L,
        )

        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = MutableStateFlow(getByIdBlocking(id))

        private fun getByIdBlocking(id: String) = CanonicalTitle(
            id = id,
            displayTitle = "Frieren",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1L,
            updatedAt = 1L,
        )

        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: tachiyomi.domain.tsuzuki.model.ExternalIdentity,
        ): CanonicalTitle = title
        override suspend fun insert(title: CanonicalTitle) {}
        override suspend fun addExternalIdentity(identity: tachiyomi.domain.tsuzuki.model.ExternalIdentity) {}
    }

    private class FakeGateway(
        private val installed: List<ReadingSourceDescriptor> = emptyList(),
        private val searchResults: Map<Long, Result<List<ReadingSourceCandidate>>> = emptyMap(),
    ) : ReadingSourceGateway {
        val searches = mutableListOf<Long>()

        override suspend fun listInstalled(language: String): List<ReadingSourceDescriptor> = installed
            .filter { it.language.equals(language, ignoreCase = true) }

        override suspend fun search(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>> {
            searches += sourceId
            return searchResults[sourceId] ?: Result.success(emptyList())
        }

        override suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource> =
            Result.success(
                MaterializedReadingSource(
                    candidate.sourceId + 1000L,
                    candidate.sourceId,
                    candidate.sourceUrl,
                    candidate.language,
                ),
            )
    }
}
