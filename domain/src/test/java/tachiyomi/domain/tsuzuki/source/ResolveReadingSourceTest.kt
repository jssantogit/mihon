package tachiyomi.domain.tsuzuki.source

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
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

    private lateinit var titleRepository: FakeCanonicalTitleRepository
    private lateinit var mappingRepository: FakeSourceTitleMappingRepository
    private lateinit var preferenceRepository: FakeReadingSourcePreferenceRepository
    private lateinit var readingSourceGateway: FakeReadingSourceGateway
    private lateinit var scoreSourceTitleMatch: ScoreSourceTitleMatch
    private lateinit var confirmSourceMapping: ConfirmSourceMapping
    private lateinit var resolveReadingSource: ResolveReadingSource

    @BeforeEach
    fun setUp() {
        titleRepository = FakeCanonicalTitleRepository()
        mappingRepository = FakeSourceTitleMappingRepository()
        preferenceRepository = FakeReadingSourcePreferenceRepository()
        readingSourceGateway = FakeReadingSourceGateway()
        scoreSourceTitleMatch = ScoreSourceTitleMatch()
        confirmSourceMapping = ConfirmSourceMapping(
            sourceTitleMappingRepository = mappingRepository,
            readingSourceGateway = readingSourceGateway,
            idFactory = { "new-mapping-id" },
            clock = { 10000L },
        )
        resolveReadingSource = ResolveReadingSource(
            canonicalTitleRepository = titleRepository,
            sourceTitleMappingRepository = mappingRepository,
            getPreferredReadingSources = GetPreferredReadingSources(preferenceRepository),
            readingSourceGateway = readingSourceGateway,
            scoreSourceTitleMatch = scoreSourceTitleMatch,
            confirmSourceMapping = confirmSourceMapping,
        )
    }

    @Test
    fun `existing mapping for title short-circuits search`() = runTest {
        val existing = SourceTitleMapping(
            id = "map-1",
            canonicalTitleId = "title-1",
            mihonMangaId = 101L,
            sourceId = 1L,
            sourceUrl = "/existing",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = true,
            availability = SourceMappingAvailability.AVAILABLE,
            preferredOverride = true,
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        mappingRepository.upsert(existing)

        val result = resolveReadingSource.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.ExistingMapping>()
        result.mapping shouldBe existing
        result.reused shouldBe true
        readingSourceGateway.searchedSourceIds shouldBe emptyList()
    }

    @Test
    fun `no preferences returns NoPreferredSources`() = runTest {
        val title = CanonicalTitle("title-1", "One Piece", CanonicalIdentityState.RESOLVED, 100L, 100L)
        titleRepository.insert(title)

        val result = resolveReadingSource.execute("title-1", "en")

        result shouldBe SourceResolutionResult.NoPreferredSources
        readingSourceGateway.searchedSourceIds shouldBe emptyList()
    }

    @Test
    fun `searches at most first 3 preferred sources in configured order`() = runTest {
        val title = CanonicalTitle("title-1", "One Piece", CanonicalIdentityState.RESOLVED, 100L, 100L)
        titleRepository.insert(title)

        preferenceRepository.replaceForLanguage("en", listOf(10L, 20L, 30L, 40L, 50L))

        val result = resolveReadingSource.execute("title-1", "en", broaden = false)

        readingSourceGateway.searchedSourceIds shouldContainExactly listOf(10L, 20L, 30L)
        result shouldBe SourceResolutionResult.NotFound
    }

    @Test
    fun `automatic acceptance when top candidate is 0_97 or greater and unambiguous`() = runTest {
        val title = CanonicalTitle("title-1", "Attack on Titan", CanonicalIdentityState.RESOLVED, 100L, 100L)
        titleRepository.insert(title)

        preferenceRepository.replaceForLanguage("en", listOf(10L))

        val candidate1 = ReadingSourceCandidate(10L, "/aot", "Attack on Titan", null)
        val candidate2 = ReadingSourceCandidate(10L, "/aot-spinoff", "Attack on Titan: Junior High", null)
        readingSourceGateway.searchResults[10L] = listOf(candidate1, candidate2)
        readingSourceGateway.materializeResult = Result.success(
            MaterializedReadingSource(10L, "/aot", 555L, "Attack on Titan"),
        )

        val result = resolveReadingSource.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.AutoAccepted>()
        result.candidate.candidate.sourceUrl shouldBe "/aot"
        result.candidate.confidence shouldBe 1.0
        result.mapping.canonicalTitleId shouldBe "title-1"
        result.mapping.mihonMangaId shouldBe 555L
        result.mapping.verifiedByUser shouldBe false
    }

    @Test
    fun `ambiguous candidates within 0_08 threshold fall through to NeedsConfirmation`() = runTest {
        val title = CanonicalTitle("title-1", "Chainsaw Man", CanonicalIdentityState.RESOLVED, 100L, 100L)
        titleRepository.insert(title)

        preferenceRepository.replaceForLanguage("en", listOf(10L))

        val candidate1 = ReadingSourceCandidate(10L, "/csm1", "Chainsaw Man", null)
        val candidate2 = ReadingSourceCandidate(10L, "/csm2", "Chainsaw Man!", null)
        readingSourceGateway.searchResults[10L] = listOf(candidate1, candidate2)

        val result = resolveReadingSource.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.NeedsConfirmation>()
        result.canonicalTitleId shouldBe "title-1"
        result.candidates shouldHaveSize 2
    }

    @Test
    fun `candidates with confidence greater or equal to 0_70 return NeedsConfirmation up to 5 ordered`() = runTest {
        val title = CanonicalTitle("title-1", "Attack on Titan", CanonicalIdentityState.RESOLVED, 100L, 100L)
        titleRepository.insert(title)

        preferenceRepository.replaceForLanguage("en", listOf(10L, 20L))

        val typo = ReadingSourceCandidate(10L, "/aot-typo", "Attack on Titn", null)
        val candidateSource2 = ReadingSourceCandidate(20L, "/aot-typo2", "Attack on Titn", null)
        readingSourceGateway.searchResults[10L] = listOf(typo)
        readingSourceGateway.searchResults[20L] = listOf(candidateSource2)

        val result = resolveReadingSource.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.NeedsConfirmation>()
        result.candidates shouldHaveSize 2
        result.candidates[0].sourcePreferenceRank shouldBe 0
        result.candidates[1].sourcePreferenceRank shouldBe 1
    }

    @Test
    fun `single source failure does not abort healthy sibling search`() = runTest {
        val title = CanonicalTitle("title-1", "Bleach", CanonicalIdentityState.RESOLVED, 100L, 100L)
        titleRepository.insert(title)

        preferenceRepository.replaceForLanguage("en", listOf(10L, 20L))
        readingSourceGateway.sourceErrors[10L] = RuntimeException("Source 10 HTTP 500")
        readingSourceGateway.searchResults[20L] = listOf(ReadingSourceCandidate(20L, "/bleach", "Bleach", null))
        readingSourceGateway.materializeResult =
            Result.success(MaterializedReadingSource(20L, "/bleach", 777L, "Bleach"))

        val result = resolveReadingSource.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.AutoAccepted>()
        result.candidate.candidate.sourceId shouldBe 20L
    }

    @Test
    fun `broaden true inspects additional installed sources`() = runTest {
        val title = CanonicalTitle("title-1", "Naruto", CanonicalIdentityState.RESOLVED, 100L, 100L)
        titleRepository.insert(title)

        preferenceRepository.replaceForLanguage("en", listOf(10L))
        readingSourceGateway.availableSources = listOf(
            ReadingSourceDescriptor(10L, "Pref Source", "en"),
            ReadingSourceDescriptor(99L, "Extra Source", "en"),
        )
        readingSourceGateway.searchResults[99L] = listOf(ReadingSourceCandidate(99L, "/naruto", "Naruto", null))
        readingSourceGateway.materializeResult =
            Result.success(MaterializedReadingSource(99L, "/naruto", 888L, "Naruto"))

        val result = resolveReadingSource.execute("title-1", "en", broaden = true)

        readingSourceGateway.searchedSourceIds shouldContainExactly listOf(10L, 99L)
        result.shouldBeInstanceOf<SourceResolutionResult.AutoAccepted>()
        result.candidate.candidate.sourceId shouldBe 99L
    }

    @Test
    fun `cross-title conflict during auto-accept results in Conflict`() = runTest {
        val title = CanonicalTitle("title-1", "Demon Slayer", CanonicalIdentityState.RESOLVED, 100L, 100L)
        titleRepository.insert(title)

        preferenceRepository.replaceForLanguage("en", listOf(10L))
        val candidate = ReadingSourceCandidate(10L, "/kimetsu", "Demon Slayer", null)
        readingSourceGateway.searchResults[10L] = listOf(candidate)

        // Mapping already exists for another canonical title
        val otherMapping = SourceTitleMapping(
            id = "other-map",
            canonicalTitleId = "other-title",
            mihonMangaId = 123L,
            sourceId = 10L,
            sourceUrl = "/kimetsu",
            language = "en",
            matchConfidence = 1.0,
            verifiedByUser = true,
            availability = SourceMappingAvailability.AVAILABLE,
            preferredOverride = false,
            createdAt = 500L,
            updatedAt = 500L,
        )
        mappingRepository.upsert(otherMapping)

        val result = resolveReadingSource.execute("title-1", "en")

        result.shouldBeInstanceOf<SourceResolutionResult.Conflict>()
        result.existingCanonicalTitleId shouldBe "other-title"
    }

    @Test
    fun `rethrows CancellationException from gateway`() = runTest {
        val title = CanonicalTitle("title-1", "One Piece", CanonicalIdentityState.RESOLVED, 100L, 100L)
        titleRepository.insert(title)

        preferenceRepository.replaceForLanguage("en", listOf(10L))
        readingSourceGateway.sourceErrors[10L] = CancellationException("Resolver cancelled")

        shouldThrow<CancellationException> {
            resolveReadingSource.execute("title-1", "en")
        }
    }

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        val titles = mutableMapOf<String, CanonicalTitle>()

        override suspend fun getById(id: String): CanonicalTitle? = titles[id]
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = emptyFlow()
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = title
        override suspend fun insert(title: CanonicalTitle) {
            titles[title.id] = title
        }
        override suspend fun addExternalIdentity(identity: ExternalIdentity) {}
    }

    private class FakeSourceTitleMappingRepository : SourceTitleMappingRepository {
        val mappings = mutableMapOf<String, SourceTitleMapping>()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> {
            return mappings.values.filter { it.canonicalTitleId == canonicalTitleId }
        }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> = emptyFlow()

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? {
            return mappings.values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }
        }

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
            prefs[language] = orderedSourceIds.mapIndexed { index, id ->
                ReadingSourcePreference(language, id, index)
            }
        }
    }

    private class FakeReadingSourceGateway : ReadingSourceGateway {
        val searchedSourceIds = mutableListOf<Long>()
        val searchResults = mutableMapOf<Long, List<ReadingSourceCandidate>>()
        val sourceErrors = mutableMapOf<Long, Throwable>()
        var availableSources = emptyList<ReadingSourceDescriptor>()
        var materializeResult: Result<MaterializedReadingSource> = Result.failure(IllegalStateException())

        override suspend fun getAvailableSources(language: String): List<ReadingSourceDescriptor> = availableSources

        override suspend fun searchSource(sourceId: Long, query: String): Result<List<ReadingSourceCandidate>> {
            searchedSourceIds.add(sourceId)
            sourceErrors[sourceId]?.let { throw it }
            return Result.success(searchResults[sourceId] ?: emptyList())
        }

        override suspend fun materializeSource(
            sourceId: Long,
            sourceUrl: String,
            title: String,
        ): Result<MaterializedReadingSource> = materializeResult
    }
}
