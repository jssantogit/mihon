package tachiyomi.data.tsuzuki.collections

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CollectionStoreImpl(
    private val database: Database,
) : CollectionStore {

    override suspend fun getCollection(id: String): TsuzukiCollection? {
        return database.tsuzuki_collectionsQueries
            .getTsuzukiCollectionById(id, ::mapCollection)
            .awaitAsOneOrNull()
    }

    override suspend fun getCollections(includeDeleted: Boolean): List<TsuzukiCollection> {
        return if (includeDeleted) {
            database.tsuzuki_collectionsQueries
                .getAllTsuzukiCollections(::mapCollection)
                .awaitAsList()
        } else {
            database.tsuzuki_collectionsQueries
                .getActiveTsuzukiCollections(::mapCollection)
                .awaitAsList()
        }
    }

    override fun observeCollections(): Flow<List<TsuzukiCollection>> {
        return database.tsuzuki_collectionsQueries
            .getActiveTsuzukiCollections(::mapCollection)
            .subscribeToList()
    }

    override suspend fun upsertCollection(collection: TsuzukiCollection) {
        database.tsuzuki_collectionsQueries.upsertTsuzukiCollection(
            id = collection.id,
            title = collection.title,
            origin = collection.origin.name,
            sortOrder = collection.sortOrder,
            schemaVersion = collection.schemaVersion.toLong(),
            revision = collection.revision,
            createdAt = collection.createdAt,
            updatedAt = collection.updatedAt,
            deletedAt = collection.deletedAt,
        )
    }

    override suspend fun getFolder(id: String): CollectionFolder? {
        return database.tsuzuki_collectionsQueries
            .getTsuzukiFolderById(id, ::mapFolder)
            .awaitAsOneOrNull()
    }

    override suspend fun getFolders(
        collectionId: String,
        includeDeleted: Boolean,
    ): List<CollectionFolder> {
        return if (includeDeleted) {
            database.tsuzuki_collectionsQueries
                .getAllTsuzukiFoldersByCollection(collectionId, ::mapFolder)
                .awaitAsList()
        } else {
            database.tsuzuki_collectionsQueries
                .getActiveTsuzukiFoldersByCollection(collectionId, ::mapFolder)
                .awaitAsList()
        }
    }

    override fun observeFolders(collectionId: String): Flow<List<CollectionFolder>> {
        return database.tsuzuki_collectionsQueries
            .getActiveTsuzukiFoldersByCollection(collectionId, ::mapFolder)
            .subscribeToList()
    }

    override suspend fun upsertFolder(folder: CollectionFolder) {
        database.transaction {
            val collection = database.tsuzuki_collectionsQueries
                .getTsuzukiCollectionById(folder.collectionId, ::mapCollection)
                .awaitAsOneOrNull()
            requireNotNull(collection) {
                "Collection ${folder.collectionId} does not exist"
            }

            val existing = database.tsuzuki_collectionsQueries
                .getTsuzukiFolderById(folder.id, ::mapFolder)
                .awaitAsOneOrNull()
            require(existing == null || existing.collectionId == folder.collectionId) {
                "Moving a persisted folder across Collections requires an explicit migration"
            }

            validateParent(folder)

            database.tsuzuki_collectionsQueries.upsertTsuzukiFolder(
                id = folder.id,
                collectionId = folder.collectionId,
                parentFolderId = folder.parentFolderId,
                title = folder.title,
                origin = folder.origin.name,
                sortOrder = folder.sortOrder,
                schemaVersion = folder.schemaVersion.toLong(),
                revision = folder.revision,
                createdAt = folder.createdAt,
                updatedAt = folder.updatedAt,
                deletedAt = folder.deletedAt,
            )
        }
    }

    override suspend fun getList(id: String): CollectionList? {
        return database.tsuzuki_collectionsQueries
            .getTsuzukiListById(id, ::mapList)
            .awaitAsOneOrNull()
    }

    override suspend fun getLists(
        folderId: String,
        includeDeleted: Boolean,
    ): List<CollectionList> {
        return if (includeDeleted) {
            database.tsuzuki_collectionsQueries
                .getAllTsuzukiListsByFolder(folderId, ::mapList)
                .awaitAsList()
        } else {
            database.tsuzuki_collectionsQueries
                .getActiveTsuzukiListsByFolder(folderId, ::mapList)
                .awaitAsList()
        }
    }

    override fun observeLists(folderId: String): Flow<List<CollectionList>> {
        return database.tsuzuki_collectionsQueries
            .getActiveTsuzukiListsByFolder(folderId, ::mapList)
            .subscribeToList()
    }

    override suspend fun upsertList(list: CollectionList) {
        database.transaction {
            val collection = database.tsuzuki_collectionsQueries
                .getTsuzukiCollectionById(list.collectionId, ::mapCollection)
                .awaitAsOneOrNull()
            requireNotNull(collection) {
                "Collection ${list.collectionId} does not exist"
            }

            val folder = database.tsuzuki_collectionsQueries
                .getTsuzukiFolderById(list.folderId, ::mapFolder)
                .awaitAsOneOrNull()
            requireNotNull(folder) {
                "Folder ${list.folderId} does not exist"
            }
            require(folder.collectionId == list.collectionId) {
                "List ${list.id} cannot reference a folder from another Collection"
            }

            val existing = database.tsuzuki_collectionsQueries
                .getTsuzukiListById(list.id, ::mapList)
                .awaitAsOneOrNull()
            require(existing == null || existing.collectionId == list.collectionId) {
                "Moving a persisted List across Collections requires an explicit migration"
            }

            database.tsuzuki_collectionsQueries.upsertTsuzukiList(
                id = list.id,
                collectionId = list.collectionId,
                folderId = list.folderId,
                title = list.title,
                providerId = list.providerId,
                queryJson = list.query?.let(CollectionQueryJsonCodec::encode),
                sort = list.sort.name,
                layoutType = list.layoutType,
                sortOrder = list.sortOrder,
                enabled = list.enabled,
                origin = list.origin.name,
                schemaVersion = list.schemaVersion.toLong(),
                revision = list.revision,
                createdAt = list.createdAt,
                updatedAt = list.updatedAt,
                deletedAt = list.deletedAt,
            )
        }
    }

    private suspend fun validateParent(folder: CollectionFolder) {
        var currentId = folder.parentFolderId ?: return
        val seen = mutableSetOf<String>()

        while (true) {
            require(seen.add(currentId)) {
                "Existing Collection folder hierarchy contains a cycle at $currentId"
            }
            require(currentId != folder.id) {
                "Folder ${folder.id} cannot become a descendant of itself"
            }

            val parent = database.tsuzuki_collectionsQueries
                .getTsuzukiFolderById(currentId, ::mapFolder)
                .awaitAsOneOrNull()
            requireNotNull(parent) {
                "Parent folder $currentId does not exist"
            }
            require(parent.collectionId == folder.collectionId) {
                "Folder ${folder.id} cannot use a parent from another Collection"
            }

            currentId = parent.parentFolderId ?: return
        }
    }

    private fun mapCollection(
        id: String,
        title: String,
        origin: String,
        sortOrder: Long,
        schemaVersion: Long,
        revision: Long,
        createdAt: Long,
        updatedAt: Long,
        deletedAt: Long?,
    ): TsuzukiCollection = TsuzukiCollection(
        id = id,
        title = title,
        origin = CollectionOrigin.valueOf(origin),
        sortOrder = sortOrder,
        schemaVersion = schemaVersion.toInt(),
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    private fun mapFolder(
        id: String,
        collectionId: String,
        parentFolderId: String?,
        title: String,
        origin: String,
        sortOrder: Long,
        schemaVersion: Long,
        revision: Long,
        createdAt: Long,
        updatedAt: Long,
        deletedAt: Long?,
    ): CollectionFolder = CollectionFolder(
        id = id,
        collectionId = collectionId,
        parentFolderId = parentFolderId,
        title = title,
        origin = CollectionOrigin.valueOf(origin),
        sortOrder = sortOrder,
        schemaVersion = schemaVersion.toInt(),
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

    private fun mapList(
        id: String,
        collectionId: String,
        folderId: String,
        title: String,
        providerId: String,
        queryJson: String?,
        sort: String,
        layoutType: String?,
        sortOrder: Long,
        enabled: Boolean,
        origin: String,
        schemaVersion: Long,
        revision: Long,
        createdAt: Long,
        updatedAt: Long,
        deletedAt: Long?,
    ): CollectionList = CollectionList(
        id = id,
        collectionId = collectionId,
        folderId = folderId,
        title = title,
        providerId = providerId,
        query = queryJson?.let(CollectionQueryJsonCodec::decode),
        sort = CatalogSort.valueOf(sort),
        layoutType = layoutType,
        sortOrder = sortOrder,
        enabled = enabled,
        origin = CollectionOrigin.valueOf(origin),
        schemaVersion = schemaVersion.toInt(),
        revision = revision,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
}
