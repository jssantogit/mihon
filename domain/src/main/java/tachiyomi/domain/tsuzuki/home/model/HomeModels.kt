package tachiyomi.domain.tsuzuki.home.model

import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.collections.model.CollectionList

data class HomeContinueReadingItem(
    val canonicalTitleId: String,
    val title: String,
    val canonicalChapterId: String,
    val chapterDisplayNumber: String,
    val lastPageRead: Long,
    val updatedAt: Long,
    val newChapterCount: Int = 0,
    val coverUrl: String? = null,
    val sourceCover: MangaCover? = null,
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

data class HomeFolderPreview(
    val folderId: String,
    val title: String,
    val previewItems: List<CatalogItem>,
)

data class HomeCollectionBrowse(
    val collectionId: String,
    val title: String,
    val folders: List<HomeFolderPreview>,
)

data class HomeFolderBrowse(
    val collectionId: String,
    val folderId: String,
    val title: String,
    val childFolders: List<HomeFolderPreview>,
    val lists: List<CollectionList>,
)

sealed interface HomeSection {
    data class CollectionSection(
        val collectionId: String,
        val title: String,
        val previewItems: List<CatalogItem>,
    ) : HomeSection
}
