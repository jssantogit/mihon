package tachiyomi.domain.tsuzuki.library.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceRepresentation
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class ObserveCanonicalLibraryTest {

    @Test
    fun `library titles include source representations grouped by canonical title`() = runTest {
        val libraryRepository = FakeCanonicalLibraryRepository()
        val sourceRepository = FakeSourceTitleMappingRepository()
        val observeLibrary = ObserveCanonicalLibrary(
            canonicalLibraryRepository = libraryRepository,
            sourceTitleMappingRepository = sourceRepository,
        )

        libraryRepository.items.value = listOf(
            libraryTitle("title-1", "One"),
            libraryTitle("title-2", "Two"),
        )
        sourceRepository.mappings.value = listOf(
            source("mapping-1", "title-1", 10L, "/one-a"),
            source("mapping-2", "title-1", 11L, "/one-b"),
            source("mapping-3", "title-2", 12L, "/two"),
        )

        val titles = observeLibrary.subscribe().firstReady()

        titles.map(LibraryTitle::id) shouldContainExactly listOf("title-1", "title-2")
        titles[0].sources.map(SourceRepresentation::id) shouldContainExactly listOf("mapping-1", "mapping-2")
        titles[1].sources.map(SourceRepresentation::id) shouldContainExactly listOf("mapping-3")
    }

    @Test
    fun `source representation changes re-emit the same library membership`() = runTest {
        val libraryRepository = FakeCanonicalLibraryRepository()
        val sourceRepository = FakeSourceTitleMappingRepository()
        val observeLibrary = ObserveCanonicalLibrary(
            canonicalLibraryRepository = libraryRepository,
            sourceTitleMappingRepository = sourceRepository,
        )

        libraryRepository.items.value = listOf(libraryTitle("title-1", "One"))

        sourceRepository.mappings.value = listOf(source("mapping-1", "title-1", 10L, "/one"))
        observeLibrary.subscribe().firstReady().single().sources.single().id shouldBe "mapping-1"

        sourceRepository.mappings.value = listOf(source("mapping-2", "title-1", 11L, "/one-new"))
        observeLibrary.subscribe().firstReady().single().sources.single().id shouldBe "mapping-2"
    }

    private suspend fun Flow<List<LibraryTitle>>.firstReady(): List<LibraryTitle> =
        this.first { it.isNotEmpty() }

    private fun libraryTitle(id: String, name: String) = LibraryTitle(
        title = CanonicalTitle(
            id = id,
            displayTitle = name,
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 100L,
            updatedAt = 100L,
        ),
        entry = CanonicalLibraryEntry(
            canonicalTitleId = id,
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 100L,
            updatedAt = 100L,
        ),
    )

    private fun source(
        id: String,
        canonicalTitleId: String,
        sourceId: Long,
        sourceUrl: String,
    ) = SourceRepresentation(
        id = id,
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = null,
        sourceId = sourceId,
        sourceUrl = sourceUrl,
        language = "en",
        matchConfidence = null,
        verifiedByUser = false,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = false,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private class FakeCanonicalLibraryRepository : CanonicalLibraryRepository {
        val items = MutableStateFlow<List<LibraryTitle>>(emptyList())

        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? = null

        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(emptyList())

        override fun getAllItemsAsFlow(): Flow<List<LibraryTitle>> = items

        override suspend fun upsert(entry: CanonicalLibraryEntry) = Unit

        override suspend fun remove(canonicalTitleId: String) = Unit
    }

    private class FakeSourceTitleMappingRepository : SourceTitleMappingRepository {
        val mappings = MutableStateFlow<List<SourceTitleMapping>>(emptyList())

        override suspend fun getAll(): List<SourceTitleMapping> = mappings.value

        override fun getAllAsFlow(): Flow<List<SourceTitleMapping>> = mappings

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.value.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(mappings.value.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            mappings.value.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) = Unit

        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) = Unit
    }
}
