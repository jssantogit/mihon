package tachiyomi.domain.tsuzuki.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.tsuzuki.library.model.LibraryTitleCategory

interface LibraryTitleCategoryRepository {
    fun getAllAsFlow(): Flow<List<LibraryTitleCategory>>
    suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<Category>
    fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<Category>>
    suspend fun setCategories(canonicalTitleId: String, categoryIds: List<Long>)
}
