package tachiyomi.domain.tsuzuki.collections.model

import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression

enum class CollectionOrigin {
    SYSTEM,
    USER,
}

data class TsuzukiCollection(
    val id: String,
    val title: String,
    val origin: CollectionOrigin,
    val sortOrder: Long,
    val schemaVersion: Int = CURRENT_COLLECTION_SCHEMA_VERSION,
    val revision: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Collection id cannot be blank" }
        require(title.isNotBlank()) { "Collection title cannot be blank" }
        require(sortOrder >= 0) { "Collection sortOrder cannot be negative" }
        require(schemaVersion > 0) { "Collection schemaVersion must be positive" }
        require(revision >= 0) { "Collection revision cannot be negative" }
    }
}

data class CollectionFolder(
    val id: String,
    val collectionId: String,
    val parentFolderId: String? = null,
    val title: String,
    val origin: CollectionOrigin,
    val sortOrder: Long,
    val schemaVersion: Int = CURRENT_COLLECTION_SCHEMA_VERSION,
    val revision: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Folder id cannot be blank" }
        require(collectionId.isNotBlank()) { "Folder collectionId cannot be blank" }
        require(parentFolderId != id) { "Folder cannot be its own parent" }
        require(title.isNotBlank()) { "Folder title cannot be blank" }
        require(sortOrder >= 0) { "Folder sortOrder cannot be negative" }
        require(schemaVersion > 0) { "Folder schemaVersion must be positive" }
        require(revision >= 0) { "Folder revision cannot be negative" }
    }
}

data class CollectionList(
    val id: String,
    val collectionId: String,
    val folderId: String,
    val title: String,
    val providerId: String,
    val query: QueryExpression?,
    val sort: CatalogSort,
    val layoutType: String? = null,
    val sortOrder: Long,
    val enabled: Boolean = true,
    val origin: CollectionOrigin,
    val schemaVersion: Int = CURRENT_COLLECTION_SCHEMA_VERSION,
    val revision: Long = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "List id cannot be blank" }
        require(collectionId.isNotBlank()) { "List collectionId cannot be blank" }
        require(folderId.isNotBlank()) { "List folderId cannot be blank" }
        require(title.isNotBlank()) { "List title cannot be blank" }
        require(providerId.isNotBlank()) { "List providerId cannot be blank" }
        require(layoutType == null || layoutType.isNotBlank()) { "List layoutType cannot be blank" }
        require(sortOrder >= 0) { "List sortOrder cannot be negative" }
        require(schemaVersion > 0) { "List schemaVersion must be positive" }
        require(revision >= 0) { "List revision cannot be negative" }
    }
}

const val CURRENT_COLLECTION_SCHEMA_VERSION: Int = 1
