package tachiyomi.domain.tsuzuki.home.model

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem

data class HomeContinueReadingItem(
    val canonicalTitleId: String,
    val title: String,
    val canonicalChapterId: String,
    val chapterDisplayNumber: String,
    val lastPageRead: Long,
    val updatedAt: Long,
    val newChapterCount: Int = 0,
) {
    init {
        require(newChapterCount >= 0) { "New chapter count must not be negative" }
    }
}

sealed interface HomeRowContent {
    data class Content(
        val items: List<CatalogItem>,
    ) : HomeRowContent

    data class Unavailable(
        val reason: String,
    ) : HomeRowContent
}

data class HomeRow(
    val listId: String,
    val title: String,
    val providerId: String,
    val layoutType: String?,
    val content: HomeRowContent,
)

sealed interface HomeSection {
    data class CollectionSection(
        val collectionId: String,
        val title: String,
        val rows: List<HomeRow>,
    ) : HomeSection
}
