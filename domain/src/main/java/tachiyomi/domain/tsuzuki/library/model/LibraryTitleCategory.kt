package tachiyomi.domain.tsuzuki.library.model

import tachiyomi.domain.category.model.Category

data class LibraryTitleCategory(
    val canonicalTitleId: String,
    val category: Category,
)
