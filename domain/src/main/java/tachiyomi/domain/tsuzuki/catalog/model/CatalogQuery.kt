package tachiyomi.domain.tsuzuki.catalog.model

data class CatalogQuery(
    val query: String? = null,
    val sort: CatalogSort = CatalogSort.POPULARITY_DESC,
    val genres: List<String> = emptyList(),
    val status: CatalogItemStatus? = null,
    val offset: Int = 0,
    val limit: Int = 20,
)
