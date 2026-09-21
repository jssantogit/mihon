package tachiyomi.domain.tsuzuki.home.model

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage

data class HomeContinueReadingItem(
    val canonicalTitleId: String,
    val title: String,
    val canonicalChapterId: String,
    val chapterDisplayNumber: String,
    val lastPageRead: Long,
    val updatedAt: Long,
    val newChapterCount: Int = 0,
)

sealed interface HomeSection {
    data class CollectionSection(
        val collectionId: String,
        val title: String,
        val rows: List<HomeRow>,
    ) : HomeSection
}

data class HomeRow(
    val listId: String,
    val title: String,
    val providerId: String,
    val layoutType: String?,
    val content: HomeRowContent,
)

sealed interface HomeRowContent {
    data class Content(
        val items: List<CatalogItem>,
    ) : HomeRowContent

    data class Unavailable(
        val reason: String,
    ) : HomeRowContent
}

/**
 * Legacy pre-Runtime-V2 feed retained only until all old callers are gone.
 * The target Home does not request these rows automatically.
 */
@Deprecated("Use HomeSection derived from user Collections")
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
