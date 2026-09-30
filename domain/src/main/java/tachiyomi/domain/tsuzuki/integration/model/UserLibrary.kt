package tachiyomi.domain.tsuzuki.integration.model

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.model.LibraryStatus

data class UserListDefinition(
    val key: String,
    val title: String,
    val status: LibraryStatus? = null,
    val selectionGroup: String? = null,
) {
    init {
        require(key.isNotBlank()) { "User list key cannot be blank" }
        require(title.isNotBlank()) { "User list title cannot be blank" }
    }
}

data class UserLibraryEntry(
    val item: CatalogItem,
    val listKeys: Set<String>,
    val status: LibraryStatus?,
    val remoteStatus: String?,
    val progress: Double? = null,
    val score: Double? = null,
    val listedAt: Long? = null,
) {
    init {
        require(listKeys.isNotEmpty()) { "User library entry must belong to at least one list" }
        require(listKeys.none(String::isBlank)) { "User library list keys cannot be blank" }
    }
}

data class UserLibrarySnapshot(
    val lists: List<UserListDefinition>,
    val entries: List<UserLibraryEntry>,
)
