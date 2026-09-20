package tachiyomi.data.tsuzuki

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.tsuzuki.library.model.LibraryTitleCategory
import tachiyomi.domain.tsuzuki.repository.LibraryTitleCategoryRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class LibraryTitleCategoryRepositoryImpl(
    private val database: Database,
) : LibraryTitleCategoryRepository {

    override fun getAllAsFlow(): Flow<List<LibraryTitleCategory>> {
        return database.tsuzuki_library_categoriesQueries
            .getAllTsuzukiLibraryCategories(::mapAssignment)
            .subscribeToList()
    }

    override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<Category> {
        return database.tsuzuki_library_categoriesQueries
            .getTsuzukiLibraryCategoriesByTitle(
                canonicalTitleId = canonicalTitleId,
                mapper = ::mapCategory,
            )
            .awaitAsList()
    }

    override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<Category>> {
        return database.tsuzuki_library_categoriesQueries
            .getTsuzukiLibraryCategoriesByTitle(
                canonicalTitleId = canonicalTitleId,
                mapper = ::mapCategory,
            )
            .subscribeToList()
    }

    override suspend fun setCategories(canonicalTitleId: String, categoryIds: List<Long>) {
        database.transaction {
            database.tsuzuki_library_categoriesQueries
                .deleteTsuzukiLibraryCategoriesByTitle(canonicalTitleId)

            categoryIds.distinct().forEach { categoryId ->
                database.tsuzuki_library_categoriesQueries.insertTsuzukiLibraryCategory(
                    canonicalTitleId = canonicalTitleId,
                    categoryId = categoryId,
                )
            }
        }
    }

    private fun mapAssignment(
        canonicalTitleId: String,
        categoryId: Long,
        name: String,
        categoryOrder: Long,
        flags: Long,
    ) = LibraryTitleCategory(
        canonicalTitleId = canonicalTitleId,
        category = mapCategory(
            categoryId = categoryId,
            name = name,
            categoryOrder = categoryOrder,
            flags = flags,
        ),
    )

    private fun mapCategory(
        categoryId: Long,
        name: String,
        categoryOrder: Long,
        flags: Long,
    ) = Category(
        id = categoryId,
        name = name,
        order = categoryOrder,
        flags = flags,
    )
}
