package tachiyomi.domain.tsuzuki.catalog.model

data class CatalogPage(
    val items: List<CatalogItem>,
    val hasNextPage: Boolean,
    val totalCount: Int? = null,
)
