package tachiyomi.domain.tsuzuki.source

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository
import tachiyomi.domain.tsuzuki.source.interactor.GetPreferredReadingSources
import tachiyomi.domain.tsuzuki.source.interactor.SetPreferredReadingSources
import tachiyomi.domain.tsuzuki.source.interactor.SetTitleSourceOverride
import tachiyomi.domain.tsuzuki.source.model.ReadingSourcePreference
import tachiyomi.domain.tsuzuki.source.repository.ReadingSourcePreferenceRepository

class ReadingSourcePreferenceTest {

    @Test
    fun `case 1 - per-language ordering stores and returns in exact position order`() = runTest {
        val repo = FakeReadingSourcePreferenceRepository()
        val getPreferred = GetPreferredReadingSources(repo)
        val setPreferred = SetPreferredReadingSources(repo)

        setPreferred.execute("en", listOf(10L, 20L, 30L))

        val preferences = getPreferred.await("en")
        preferences.size shouldBe 3
        preferences[0] shouldBe ReadingSourcePreference("en", 10L, 0)
        preferences[1] shouldBe ReadingSourcePreference("en", 20L, 1)
        preferences[2] shouldBe ReadingSourcePreference("en", 30L, 2)
    }

    @Test
    fun `case 2 - language isolation ensures updating one language leaves others untouched`() = runTest {
        val repo = FakeReadingSourcePreferenceRepository()
        val setPreferred = SetPreferredReadingSources(repo)
        val getPreferred = GetPreferredReadingSources(repo)

        setPreferred.execute("en", listOf(10L, 20L))
        setPreferred.execute("ja", listOf(30L, 40L))

        // Update "en"
        setPreferred.execute("en", listOf(50L))

        val enPrefs = getPreferred.await("en")
        val jaPrefs = getPreferred.await("ja")

        enPrefs shouldBe listOf(ReadingSourcePreference("en", 50L, 0))
        jaPrefs shouldBe listOf(
            ReadingSourcePreference("ja", 30L, 0),
            ReadingSourcePreference("ja", 40L, 1),
        )
    }

    @Test
    fun `case 3 - duplicate input rejection throws IllegalArgumentException`() = runTest {
        val repo = FakeReadingSourcePreferenceRepository()
        val setPreferred = SetPreferredReadingSources(repo)

        shouldThrow<IllegalArgumentException> {
            setPreferred.execute("en", listOf(10L, 20L, 10L))
        }
    }

    @Test
    fun `case 4 - override replacement sets new preferred mapping and clears previous`() = runTest {
        val mappingRepo = FakeSourceTitleMappingRepository()
        val overrideInteractor = SetTitleSourceOverride(mappingRepo, clock = { 2000L })

        val mapping1 = createMapping("m-1", "title-1", 10L, preferred = true)
        val mapping2 = createMapping("m-2", "title-1", 20L, preferred = false)
        mappingRepo.upsert(mapping1)
        mappingRepo.upsert(mapping2)

        overrideInteractor.execute("title-1", "m-2")

        val updated1 = mappingRepo.getByCanonicalTitleId("title-1").first { it.id == "m-1" }
        val updated2 = mappingRepo.getByCanonicalTitleId("title-1").first { it.id == "m-2" }

        updated1.preferredOverride shouldBe false
        updated2.preferredOverride shouldBe true
        updated2.updatedAt shouldBe 2000L
    }

    @Test
    fun `case 5 - cross-title override rejection throws IllegalArgumentException`() = runTest {
        val mappingRepo = FakeSourceTitleMappingRepository()
        val overrideInteractor = SetTitleSourceOverride(mappingRepo)

        val mappingA = createMapping("m-a", "title-A", 10L)
        val mappingB = createMapping("m-b", "title-B", 20L)
        mappingRepo.upsert(mappingA)
        mappingRepo.upsert(mappingB)

        shouldThrow<IllegalArgumentException> {
            overrideInteractor.execute("title-A", "m-b")
        }
    }

    @Test
    fun `case 6 - null mappingId clears title override on all mappings for that title`() = runTest {
        val mappingRepo = FakeSourceTitleMappingRepository()
        val overrideInteractor = SetTitleSourceOverride(mappingRepo, clock = { 3000L })

        val mapping1 = createMapping("m-1", "title-1", 10L, preferred = true)
        val mapping2 = createMapping("m-2", "title-1", 20L, preferred = false)
        mappingRepo.upsert(mapping1)
        mappingRepo.upsert(mapping2)

        overrideInteractor.execute("title-1", null)

        val mappings = mappingRepo.getByCanonicalTitleId("title-1")
        mappings.all { !it.preferredOverride } shouldBe true
    }

    @Test
    fun `case 7 - configured languages returns distinct languages sorted`() = runTest {
        val repo = FakeReadingSourcePreferenceRepository()
        val setPreferred = SetPreferredReadingSources(repo)
        val getPreferred = GetPreferredReadingSources(repo)

        setPreferred.execute("ja", listOf(10L))
        setPreferred.execute("en", listOf(20L))
        setPreferred.execute("fr", listOf(30L))

        val languages = getPreferred.getConfiguredLanguages()
        languages shouldBe listOf("en", "fr", "ja")
    }

    @Test
    fun `case 8 - observeForLanguage reflects updated preferences reactively`() = runTest {
        val repo = FakeReadingSourcePreferenceRepository()
        val getPreferred = GetPreferredReadingSources(repo)
        val setPreferred = SetPreferredReadingSources(repo)

        setPreferred.execute("en", listOf(100L))
        val initial = getPreferred.subscribe("en").first()
        initial shouldBe listOf(ReadingSourcePreference("en", 100L, 0))

        setPreferred.execute("en", listOf(200L, 300L))
        val updated = getPreferred.subscribe("en").first()
        updated shouldBe listOf(
            ReadingSourcePreference("en", 200L, 0),
            ReadingSourcePreference("en", 300L, 1),
        )
    }

    @Test
    fun `case 9 - non-existent mapping rejection throws IllegalArgumentException`() = runTest {
        val mappingRepo = FakeSourceTitleMappingRepository()
        val overrideInteractor = SetTitleSourceOverride(mappingRepo)

        shouldThrow<IllegalArgumentException> {
            overrideInteractor.execute("title-1", "non-existent-mapping")
        }
    }

    @Test
    fun `case 10 - empty preference list clears preferences for that language`() = runTest {
        val repo = FakeReadingSourcePreferenceRepository()
        val setPreferred = SetPreferredReadingSources(repo)
        val getPreferred = GetPreferredReadingSources(repo)

        setPreferred.execute("en", listOf(10L, 20L))
        getPreferred.await("en").size shouldBe 2

        setPreferred.execute("en", emptyList())
        getPreferred.await("en").shouldBe(emptyList())
    }

    private fun createMapping(
        id: String,
        canonicalTitleId: String,
        sourceId: Long,
        preferred: Boolean = false,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = 1L,
        sourceId = sourceId,
        sourceUrl = "/test/$id",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = false,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = preferred,
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    private class FakeReadingSourcePreferenceRepository : ReadingSourcePreferenceRepository {
        private val storage = mutableMapOf<String, MutableList<ReadingSourcePreference>>()
        private val flows = mutableMapOf<String, MutableStateFlow<List<ReadingSourcePreference>>>()

        override suspend fun getForLanguage(language: String): List<ReadingSourcePreference> {
            return storage[language]?.toList() ?: emptyList()
        }

        override fun observeForLanguage(language: String): Flow<List<ReadingSourcePreference>> {
            return flows.getOrPut(language) { MutableStateFlow(storage[language]?.toList() ?: emptyList()) }
        }

        override suspend fun getConfiguredLanguages(): List<String> {
            return storage.filter { it.value.isNotEmpty() }.keys.sorted()
        }

        override suspend fun replaceForLanguage(language: String, orderedSourceIds: List<Long>) {
            val unique = orderedSourceIds.distinct()
            require(unique.size == orderedSourceIds.size) { "Duplicate source IDs" }

            val list = orderedSourceIds.mapIndexed { index, sourceId ->
                ReadingSourcePreference(language, sourceId, index)
            }.toMutableList()

            storage[language] = list
            flows.getOrPut(language) { MutableStateFlow(emptyList()) }.value = list
        }
    }

    private class FakeSourceTitleMappingRepository : SourceTitleMappingRepository {
        val mappings = mutableMapOf<String, SourceTitleMapping>()

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> {
            return mappings.values.filter { it.canonicalTitleId == canonicalTitleId }
        }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> {
            return MutableStateFlow(mappings.values.filter { it.canonicalTitleId == canonicalTitleId })
        }

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? {
            return mappings.values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }
        }

        override suspend fun upsert(mapping: SourceTitleMapping) {
            mappings[mapping.id] = mapping
        }

        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) {
            if (mappingId != null) {
                val target = mappings[mappingId] ?: throw IllegalArgumentException("Mapping not found")
                require(target.canonicalTitleId == canonicalTitleId) {
                    "Mapping $mappingId does not belong to title $canonicalTitleId"
                }
            }
            mappings.values.filter { it.canonicalTitleId == canonicalTitleId }.forEach {
                mappings[it.id] = it.copy(
                    preferredOverride = it.id == mappingId,
                    updatedAt = updatedAt,
                )
            }
        }
    }
}
