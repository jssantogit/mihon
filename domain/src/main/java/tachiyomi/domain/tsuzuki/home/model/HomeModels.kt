package tachiyomi.domain.tsuzuki.home.model

import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage

data class HomeContinueReadingItem(
    val canonicalTitleId: String,
    val title: String,
    val canonicalChapterId: String,
    val chapterDisplayNumber: String,
    val lastPageRead: Long,
    val updatedAt: Long,
)

data class HomeCatalogFeed(
    val recentlyUpdated: Result<CatalogPage>,
    val trending: Result<CatalogPage>,
    val popular: Result<CatalogPage>,
) {
    val isCompleteFailure: Boolean
        get() = recentlyUpdated.isFailure && trending.isFailure && popular.isFailure

    val isDegraded: Boolean
        get() = recentlyUpdated.isFailure || trending.isFailure || popular.isFailure
}
