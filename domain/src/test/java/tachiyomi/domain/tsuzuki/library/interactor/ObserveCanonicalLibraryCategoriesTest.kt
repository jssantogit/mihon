package tachiyomi.domain.tsuzuki.library.interactor

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.tsuzuki.library.model.LibraryTitle
import tachiyomi.domain.tsuzuki.library.model.LibraryTitleCategory
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.LibraryTitleCategoryRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

class ObserveCanonicalLibraryCategoriesTest {

    @Test
    fun `library titles aggregate shared mihon categories by canonical identity`() = runTest {
        val library = FakeLibraryRepository(
            listOf(
                libraryTitle("title-1", "One"),
                libraryTitle("title-2", "Two"),
            ),
        )
        val categories = FakeLibraryTitleCategoryRepository(
            listOf(
                LibraryTitleCategory("title-1", category(1L, "Reading")),
                LibraryTitleCategory("title-1", category(2L, "Favorites")),
                LibraryTitleCategory("title-2", category(2L, "Favorites")),
            ),
        )
        val observeLibrary = ObserveCanonicalLibrary(
            canonicalLibraryRepository = library,
            sourceTitleMappingRepository = EmptySourceMappings,
            libraryTitleCategoryRepository = categories,
        )

        val result = observeLibrary.subscribe().first()

        result[0].categories.map(Category::id) shouldContainExactly listOf(1L, 2L)
        result[1].categories.map(Category::id) shouldContainExactly listOf(2L)
    }

    private fun category(id: Long, name: String) = Category(
        id = id,
        name = name,
        order = id,
        flags = 0L,
    )

    private fun libraryTitle(id: String, title: String) = LibraryTitle(
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

    private class FakeLibraryRepository(
        items: List<LibraryTitle>,
    ) : CanonicalLibraryRepository {
        private val itemsFlow = MutableStateFlow(items)

        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? = null
        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(emptyList())
        override fun getAllItemsAsFlow(): Flow<List<LibraryTitle>> = itemsFlow
        override suspend fun upsert(entry: CanonicalLibraryEntry) = Unit
        override suspend fun remove(canonicalTitleId: String) = Unit
    }

    private class FakeLibraryTitleCategoryRepository(
        assignments: List<LibraryTitleCategory>,
    ) : LibraryTitleCategoryRepository {
        private val assignmentsFlow = MutableStateFlow(assignments)

        override fun getAllAsFlow(): Flow<List<LibraryTitleCategory>> = assignmentsFlow

        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<Category> =
            assignmentsFlow.value
                .filter { it.canonicalTitleId == canonicalTitleId }
                .map { it.category }

        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<Category>> =
            MutableStateFlow(
                assignmentsFlow.value
                    .filter { it.canonicalTitleId == canonicalTitleId }
                    .map { it.category },
            )

        override suspend fun setCategories(canonicalTitleId: String, categoryIds: List<Long>) = Unit
    }

    private object EmptySourceMappings : SourceTitleMappingRepository {
        override suspend fun getAll(): List<SourceTitleMapping> = emptyList()
        override fun getAllAsFlow(): Flow<List<SourceTitleMapping>> = MutableStateFlow(emptyList())
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> = emptyList()
        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> =
            MutableStateFlow(emptyList())
        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? = null
        override suspend fun upsert(mapping: SourceTitleMapping) = Unit
        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) = Unit
    }
}
