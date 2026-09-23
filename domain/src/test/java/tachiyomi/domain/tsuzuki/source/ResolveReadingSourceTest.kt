package tachiyomi.domain.tsuzuki.source

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.ConfirmSourceMapping
import tachiyomi.domain.tsuzuki.source.interactor.GetPreferredReadingSources
import tachiyomi.domain.tsuzuki.source.interactor.ResolveReadingSource
import tachiyomi.domain.tsuzuki.source.interactor.ScoreSourceTitleMatch
import tachiyomi.domain.tsuzuki.source.model.MaterializedReadingSource
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceCandidate
import tachiyomi.domain.tsuzuki.source.model.ReadingSourceDescriptor
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.model.SourceResolutionResult
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository
import tachiyomi.domain.tsuzuki.source.service.ReadingSourceGateway

class ResolveReadingSourceTest {

    private lateinit var titles: FakeCanonicalTitleRepository
    private lateinit var mappings: FakeSourceTitleMappingRepository
    private lateinit var preferences: FakeReadingSourcePreferenceRepository
    private lateinit var gateway: FakeReadingSourceGateway
    private lateinit var resolver: ResolveReadingSource

    @BeforeEach
    fun setUp() {
        titles = FakeCanonicalTitleRepository()
        mappings = FakeSourceTitleMappingRepository()
        preferences = FakeReadingSourcePreferenceRepository()
        gateway = FakeReadingSourceGateway()
        resolver = ResolveReadingSource(
            canonicalTitleRepository = titles,
            sourceTitleMappingRepository = mappings,
            getPreferredReadingSources = GetPreferredReadingSources(preferences),
            readingSourceGateway = gateway,
            scoreSourceTitleMatch = ScoreSourceTitleMatch(),
            confirmSourceMapping = ConfirmSourceMapping(
                sourceTitleMappingRepository = mappings,
                readingSourceGateway = gateway,
                idFactory = { "new-mapping" },
                clock = { 1000L },
            ),
        )
    }

    @Test
    fun `persisted preferred mapping short-circuits every search`() = runTest {
        val languageMapping = mapping("language", "title-1", 10L, "en", preferred = false)
        val override = mapping("override", "title-1", 20L, "ja", preferred = true)
        mappings.upsert(languageMapping)
        mappings.upsert(override)

        val result = resolver.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.Resolved>()
        result.mapping shouldBe override
        result.reused shouldBe true
        gateway.searchedSourceIds shouldBe emptyList()
    }

    @Test
    fun `persisted incomplete mapping is materialized instead of blindly reused`() = runTest {
        titles.insert(title("title-1", "Tokyo Ghoul"))
        mappings.upsert(
            mapping(
                id = "existing",
                canonicalTitleId = "title-1",
                sourceId = 10L,
                language = "en",
                mihonMangaId = null,
                sourceUrl = "/tokyo-ghoul",
            ),
        )
        gateway.materializeResult = Result.success(
            MaterializedReadingSource(555L, 10L, "/tokyo-ghoul", "en"),
        )

        val result = resolver.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.Resolved>()
        result.reused shouldBe true
        result.mapping.mihonMangaId shouldBe 555L
        gateway.materializeCallCount shouldBe 1
        gateway.searchedSourceIds shouldBe emptyList()
    }

    @Test
    fun `no preferences returns requested language`() = runTest {
        titles.insert(title("title-1", "One Piece"))

        resolver.execute("title-1", "en") shouldBe SourceResolutionResult.NoPreferredSources("en")
    }

    @Test
    fun `normal path searches at most first three preferred sources and reports broaden capability`() = runTest {
        titles.insert(title("title-1", "One Piece"))
        preferences.replaceForLanguage("en", listOf(10L, 20L, 30L, 40L))

        val result = resolver.execute("title-1", "en")

        gateway.searchedSourceIds shouldContainExactly listOf(10L, 20L, 30L)
        result shouldBe SourceResolutionResult.NotFound(
            searchedSourceIds = listOf(10L, 20L, 30L),
            canBroaden = true,
        )
    }

    @Test
    fun `not found disables broaden when no additional source exists`() = runTest {
        titles.insert(title("title-1", "One Piece"))
        preferences.replaceForLanguage("en", listOf(10L))
        gateway.availableSources = listOf(ReadingSourceDescriptor(10L, "Only", "en"))

        resolver.execute("title-1", "en") shouldBe SourceResolutionResult.NotFound(
            searchedSourceIds = listOf(10L),
            canBroaden = false,
        )
    }

    @Test
    fun `unique exact candidate auto resolves without searching later source`() = runTest {
        titles.insert(title("title-1", "Attack on Titan"))
        preferences.replaceForLanguage("en", listOf(10L, 20L))
        gateway.searchResults[10L] = listOf(candidate(10L, "/aot", "Attack on Titan"))
        gateway.materializeResult = Result.success(MaterializedReadingSource(555L, 10L, "/aot", "en"))

        val result = resolver.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.Resolved>()
        result.reused shouldBe false
        result.mapping.mihonMangaId shouldBe 555L
        result.mapping.verifiedByUser shouldBe false
        gateway.searchedSourceIds shouldContainExactly listOf(10L)
    }

    @Test
    fun `ambiguous high confidence candidates require confirmation`() = runTest {
        titles.insert(title("title-1", "Chainsaw Man"))
        preferences.replaceForLanguage("en", listOf(10L))
        gateway.searchResults[10L] = listOf(
            candidate(10L, "/one", "Chainsaw Man"),
            candidate(10L, "/two", "Chainsaw Man!"),
        )

        val result = resolver.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.NeedsConfirmation>()
        result.candidates.size shouldBe 2
    }

    @Test
    fun `confirmation candidates are limited to five and ordered by source preference then confidence`() = runTest {
        titles.insert(title("title-1", "Attack on Titan"))
        preferences.replaceForLanguage("en", listOf(10L, 20L))
        gateway.searchResults[10L] = listOf(
            candidate(10L, "/a", "Attack on Titn"),
            candidate(10L, "/b", "Attack on Tita"),
            candidate(10L, "/c", "Attack Titan"),
        )
        gateway.searchResults[20L] = listOf(
            candidate(20L, "/d", "Attack on Titn"),
            candidate(20L, "/e", "Attack on Tita"),
            candidate(20L, "/f", "Attack Titan"),
        )

        val result = resolver.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.NeedsConfirmation>()
        result.candidates.size shouldBe 5
        result.candidates.takeWhile { it.sourcePreferenceRank == 0 }.isNotEmpty() shouldBe true
    }

    @Test
    fun `one source failure does not cancel healthy siblings`() = runTest {
        titles.insert(title("title-1", "Bleach"))
        preferences.replaceForLanguage("en", listOf(10L, 20L))
        gateway.sourceErrors[10L] = RuntimeException("HTTP 500")
        gateway.searchResults[20L] = listOf(candidate(20L, "/bleach", "Bleach"))
        gateway.materializeResult = Result.success(MaterializedReadingSource(777L, 20L, "/bleach", "en"))

        val result = resolver.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.Resolved>()
        gateway.searchedSourceIds shouldContainExactly listOf(10L, 20L)
    }

    @Test
    fun `all source failures produce NotFound with attempted ids`() = runTest {
        titles.insert(title("title-1", "Bleach"))
        preferences.replaceForLanguage("en", listOf(10L, 20L))
        gateway.sourceErrors[10L] = RuntimeException("one")
        gateway.sourceErrors[20L] = RuntimeException("two")
        gateway.availableSources = listOf(
            ReadingSourceDescriptor(10L, "One", "en"),
            ReadingSourceDescriptor(20L, "Two", "en"),
        )

        resolver.execute("title-1", "en") shouldBe SourceResolutionResult.NotFound(
            searchedSourceIds = listOf(10L, 20L),
            canBroaden = false,
        )
    }

    @Test
    fun `broaden explicitly includes remaining installed sources`() = runTest {
        titles.insert(title("title-1", "Naruto"))
        preferences.replaceForLanguage("en", listOf(10L))
        gateway.availableSources = listOf(
            ReadingSourceDescriptor(10L, "Preferred", "en"),
            ReadingSourceDescriptor(99L, "Extra", "en"),
        )
        gateway.searchResults[99L] = listOf(candidate(99L, "/naruto", "Naruto"))
        gateway.materializeResult = Result.success(MaterializedReadingSource(888L, 99L, "/naruto", "en"))

        val result = resolver.execute("title-1", "en", broaden = true)

        gateway.searchedSourceIds shouldContainExactly listOf(10L, 99L)
        result.shouldBeInstanceOf<SourceResolutionResult.Resolved>()
    }

    @Test
    fun `cross-title auto-accept conflict fails closed`() = runTest {
        titles.insert(title("title-1", "Demon Slayer"))
        preferences.replaceForLanguage("en", listOf(10L))
        gateway.searchResults[10L] = listOf(candidate(10L, "/kimetsu", "Demon Slayer"))
        mappings.upsert(mapping("other", "other-title", 10L, "en", sourceUrl = "/kimetsu"))

        val result = resolver.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.Conflict>()
        result.existingCanonicalTitleId shouldBe "other-title"
        gateway.materializeCallCount shouldBe 0
    }

    @Test
    fun `cancellation from source search propagates`() = runTest {
        titles.insert(title("title-1", "One Piece"))
        preferences.replaceForLanguage("en", listOf(10L))
        gateway.sourceErrors[10L] = CancellationException("cancelled")

        shouldThrow<CancellationException> {
            resolver.execute("title-1", "en")
        }
    }

    private fun title(id: String, name: String) =
        CanonicalTitle(id, name, CanonicalIdentityState.RESOLVED, 100L, 100L)

    private fun candidate(sourceId: Long, url: String, name: String) = ReadingSourceCandidate(
        sourceId = sourceId,
        sourceName = "Source $sourceId",
        language = "en",
        sourceUrl = url,
        title = name,
        thumbnailUrl = null,
        author = null,
        artist = null,
        description = null,
        genres = null,
        status = 0L,
    )

    private fun mapping(
        id: String,
        canonicalTitleId: String,
        sourceId: Long,
        language: String,
        preferred: Boolean = false,
        mihonMangaId: Long? = 1L,
        sourceUrl: String = "/$id",
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = mihonMangaId,
        sourceId = sourceId,
        sourceUrl = sourceUrl,
        language = language,
        matchConfidence = 1.0,
        verifiedByUser = true,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = preferred,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        val titles = mutableMapOf<String, CanonicalTitle>()
        override suspend fun getById(id: String): CanonicalTitle? = titles[id]
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = emptyFlow()
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle =
            title
        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
        }
        override suspend fun addExternalIdentity(identity: ExternalIdentity) {}
    }

    private class FakeSourceTitleMappingRepository : SourceTitleMappingRepository {
        val mappings = mutableMapOf<String, SourceTitleMapping>()
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.values.filter { it.canonicalTitleId == canonicalTitleId }
        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> = emptyFlow()
        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            mappings.values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }
        override suspend fun upsert(mapping: SourceTitleMapping) {
            mappings[mapping.id] = mapping
        }
        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) {}
    }

    private class FakeReadingSourcePreferenceRepository : ReadingSourcePreferenceRepository {
        private val prefs = mutableMapOf<String, List<ReadingSourcePreference>>()
        override suspend fun getForLanguage(language: String): List<ReadingSourcePreference> =
            prefs[language] ?: emptyList()
        override fun observeForLanguage(language: String): Flow<List<ReadingSourcePreference>> =
            MutableStateFlow(prefs[language] ?: emptyList())
        override suspend fun getConfiguredLanguages(): List<String> = prefs.keys.sorted()
        override suspend fun replaceForLanguage(language: String, orderedSourceIds: List<Long>) {
            prefs[language] = orderedSourceIds.mapIndexed { index, id -> ReadingSourcePreference(language, id, index) }
        }
    }

    private class FakeReadingSourceGateway : ReadingSourceGateway {
        val searchedSourceIds = mutableListOf<Long>()
        val searchResults = mutableMapOf<Long, List<ReadingSourceCandidate>>()
        val sourceErrors = mutableMapOf<Long, Throwable>()
        var availableSources = emptyList<ReadingSourceDescriptor>()
        var materializeResult: Result<MaterializedReadingSource> = Result.failure(IllegalStateException())
        var materializeCallCount = 0

        override suspend fun listInstalled(language: String): List<ReadingSourceDescriptor> = availableSources

        override suspend fun search(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>> {
            searchedSourceIds += sourceId
            sourceErrors[sourceId]?.let { throw it }
            return Result.success(searchResults[sourceId] ?: emptyList())
        }

        override suspend fun materialize(candidate: ReadingSourceCandidate): Result<MaterializedReadingSource> {
            materializeCallCount++
            return materializeResult
        }
    }
}
