package tachiyomi.domain.tsuzuki.catalog.model

data class CatalogItem(
    val provider: String,
    val providerId: String,
    val title: String,
    val titles: Map<String, String> = emptyMap(),
    val synopsis: String? = null,
    val coverUrl: String? = null,
    val bannerUrl: String? = null,
    val status: CatalogItemStatus = CatalogItemStatus.UNKNOWN,
    val format: CatalogItemFormat = CatalogItemFormat.UNKNOWN,
    val score: CatalogScore? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val startDate: String? = null,
    val endDate: String? = null,
    val chapterCount: Int? = null,
    val volumeCount: Int? = null,
)
