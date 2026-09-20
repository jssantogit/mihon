package tachiyomi.domain.tsuzuki.library.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class LinkMigratedSourceRepresentationTest {

    @Test
    fun `explicit migration links target source to origin canonical identity`() = runTest {
        val mappings = FakeMappings(
            source(
                id = "source-a",
                canonicalTitleId = "canonical-a",
                sourceId = 1L,
                sourceUrl = "/a",
                mihonMangaId = 10L,
            ),
        )
        val library = FakeLibrary("canonical-a")
        val interactor = LinkMigratedSourceRepresentation(
            sourceTitleMappingRepository = mappings,
            canonicalLibraryRepository = library,
            mappingIdFactory = { "source-b" },
            clock = { 200L },
        )

        val result = interactor.execute(
            originSourceId = 1L,
            originSourceUrl = "/a",
            targetMihonMangaId = 20L,
            targetSourceId = 2L,
            targetSourceUrl = "/b",
            targetLanguage = "en",
        )

        result shouldBe "canonical-a"
        mappings.getBySource(2L, "/b")?.canonicalTitleId shouldBe "canonical-a"
        mappings.getBySource(2L, "/b")?.verifiedByUser shouldBe true
        mappings.preferred shouldBe "source-b"
        library.entries.keys shouldBe setOf("canonical-a")
    }

    @Test
    fun `explicit migration repairs target mapping that previously created duplicate canonical title`() = runTest {
        val mappings = FakeMappings(
            source(
                id = "source-a",
                canonicalTitleId = "canonical-a",
                sourceId = 1L,
                sourceUrl = "/a",
                mihonMangaId = 10L,
            ),
            source(
                id = "source-b",
                canonicalTitleId = "canonical-duplicate",
                sourceId = 2L,
                sourceUrl = "/b",
                mihonMangaId = 20L,
            ),
        )
        val library = FakeLibrary("canonical-a", "canonical-duplicate")
        val interactor = LinkMigratedSourceRepresentation(
            sourceTitleMappingRepository = mappings,
            canonicalLibraryRepository = library,
            mappingIdFactory = { error("must reuse target mapping") },
            clock = { 200L },
        )

        interactor.execute(
            originSourceId = 1L,
            originSourceUrl = "/a",
            targetMihonMangaId = 20L,
            targetSourceId = 2L,
            targetSourceUrl = "/b",
            targetLanguage = "en",
        )

        mappings.getBySource(2L, "/b")?.id shouldBe "source-b"
        mappings.getBySource(2L, "/b")?.canonicalTitleId shouldBe "canonical-a"
        mappings.removedIds shouldBe listOf("source-b")
        library.entries.keys shouldBe setOf("canonical-a")
    }

    @Test
    fun `migration never links by title when origin source mapping is missing`() = runTest {
        val mappings = FakeMappings()
        val library = FakeLibrary("canonical-unrelated")
        val interactor = LinkMigratedSourceRepresentation(
            sourceTitleMappingRepository = mappings,
            canonicalLibraryRepository = library,
            mappingIdFactory = { error("must not create mapping") },
            clock = { 200L },
        )

        val result = interactor.execute(
            originSourceId = 1L,
            originSourceUrl = "/a",
            targetMihonMangaId = 20L,
            targetSourceId = 2L,
            targetSourceUrl = "/b",
            targetLanguage = "en",
        )

        result shouldBe null
        mappings.getBySource(2L, "/b") shouldBe null
        library.entries.keys shouldBe setOf("canonical-unrelated")
    }

    private fun source(
        id: String,
        canonicalTitleId: String,
        sourceId: Long,
        sourceUrl: String,
        mihonMangaId: Long?,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = mihonMangaId,
        sourceId = sourceId,
        sourceUrl = sourceUrl,
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = true,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = false,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private class FakeMappings(
        vararg initial: SourceTitleMapping,
    ) : SourceTitleMappingRepository {
        private val mappings = initial.associateBy { it.id }.toMutableMap()
        val removedIds = mutableListOf<String>()
        var preferred: String? = null

        override suspend fun getAll(): List<SourceTitleMapping> = mappings.values.toList()
        override fun getAllAsFlow(): Flow<List<SourceTitleMapping>> = MutableStateFlow(mappings.values.toList())
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String) =
            mappings.values.filter { it.canonicalTitleId == canonicalTitleId }
        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String) =
            MutableStateFlow(mappings.values.filter { it.canonicalTitleId == canonicalTitleId })
        override suspend fun getBySource(sourceId: Long, sourceUrl: String) =
            mappings.values.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }
        override suspend fun upsert(mapping: SourceTitleMapping) {
            mappings[mapping.id] = mapping
        }
        override suspend fun remove(id: String) {
            removedIds += id
            mappings.remove(id)
        }
        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) {
            preferred = mappingId
            mappings.replaceAll { id, mapping ->
                if (mapping.canonicalTitleId == canonicalTitleId) {
                    mapping.copy(preferredOverride = id == mappingId, updatedAt = updatedAt)
                } else {
                    mapping
                }
            }
        }
    }

    private class FakeLibrary(
        vararg ids: String,
    ) : CanonicalLibraryRepository {
        val entries = ids.associateWith {
            CanonicalLibraryEntry(
                canonicalTitleId = it,
                status = LibraryStatus.READING,
                favorite = true,
                addedAt = 100L,
                updatedAt = 100L,
            )
        }.toMutableMap()

        override suspend fun get(canonicalTitleId: String) = entries[canonicalTitleId]
        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(entries.values.toList())
        override fun getAllItemsAsFlow() =
            MutableStateFlow(emptyList<tachiyomi.domain.tsuzuki.library.model.LibraryTitle>())
        override suspend fun upsert(entry: CanonicalLibraryEntry) {
            entries[entry.canonicalTitleId] = entry
        }
        override suspend fun remove(canonicalTitleId: String) {
            entries.remove(canonicalTitleId)
        }
    }
}
