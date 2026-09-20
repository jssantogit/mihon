package tachiyomi.domain.tsuzuki.library.interactor

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceMappingAvailability
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class GetLibraryTitlesForUpdateTest {

    @Test
    fun `canonical membership is enriched with every source representation without duplicating the title`() = runTest {
        val library = FakeCanonicalLibraryRepository(
            listOf(libraryItem("title-1", "Title One")),
        )
        val mappings = FakeSourceTitleMappingRepository(
            listOf(
                mapping("mapping-1", "title-1", sourceId = 1L, preferred = false),
                mapping("mapping-2", "title-1", sourceId = 2L, preferred = true),
            ),
        )

        val result = GetLibraryTitlesForUpdate(library, mappings).await()

        result.size shouldBe 1
        result.single().id shouldBe "title-1"
        result.single().sources.map { it.id } shouldContainExactly listOf("mapping-1", "mapping-2")
    }

    @Test
    fun `source representations are attached only to their canonical title`() = runTest {
        val library = FakeCanonicalLibraryRepository(
            listOf(
                libraryItem("title-1", "Title One"),
                libraryItem("title-2", "Title Two"),
            ),
        )
        val mappings = FakeSourceTitleMappingRepository(
            listOf(
                mapping("mapping-2", "title-2", sourceId = 2L),
                mapping("mapping-1", "title-1", sourceId = 1L),
            ),
        )

        val result = GetLibraryTitlesForUpdate(library, mappings).await()

        result.map { it.id } shouldContainExactly listOf("title-1", "title-2")
        result[0].sources.map { it.canonicalTitleId } shouldBe listOf("title-1")
        result[1].sources.map { it.canonicalTitleId } shouldBe listOf("title-2")
    }

    private fun libraryItem(id: String, title: String) = CanonicalLibraryItem(
        title = CanonicalTitle(
            id = id,
            displayTitle = title,
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

    private fun mapping(
        id: String,
        canonicalTitleId: String,
        sourceId: Long,
        preferred: Boolean = false,
    ) = SourceTitleMapping(
        id = id,
        canonicalTitleId = canonicalTitleId,
        mihonMangaId = sourceId * 10,
        sourceId = sourceId,
        sourceUrl = "/$id",
        language = "en",
        matchConfidence = 1.0,
        verifiedByUser = true,
        availability = SourceMappingAvailability.AVAILABLE,
        preferredOverride = preferred,
        createdAt = 100L,
        updatedAt = 100L,
    )

    private class FakeCanonicalLibraryRepository(
        items: List<CanonicalLibraryItem>,
    ) : CanonicalLibraryRepository {
        private val itemsFlow = MutableStateFlow(items)

        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? =
            itemsFlow.value.firstOrNull { it.id == canonicalTitleId }?.entry

        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> =
            MutableStateFlow(itemsFlow.value.map { it.entry })

        override fun getAllItemsAsFlow(): Flow<List<CanonicalLibraryItem>> = itemsFlow

        override suspend fun upsert(entry: CanonicalLibraryEntry) = Unit

        override suspend fun remove(canonicalTitleId: String) = Unit
    }

    private class FakeSourceTitleMappingRepository(
        private val mappings: List<SourceTitleMapping>,
    ) : SourceTitleMappingRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> =
            mappings.filter { it.canonicalTitleId == canonicalTitleId }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(mappings.filter { it.canonicalTitleId == canonicalTitleId })

        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? =
            mappings.firstOrNull { it.sourceId == sourceId && it.sourceUrl == sourceUrl }

        override suspend fun upsert(mapping: SourceTitleMapping) = Unit

        override suspend fun setPreferredForTitle(
            canonicalTitleId: String,
            mappingId: String?,
            updatedAt: Long,
        ) = Unit
    }
}
