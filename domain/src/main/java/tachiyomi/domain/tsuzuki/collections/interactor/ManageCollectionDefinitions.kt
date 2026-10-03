package tachiyomi.domain.tsuzuki.collections.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import java.util.UUID
import kotlin.time.Clock

class ManageCollectionDefinitions internal constructor(
    private val store: CollectionStore,
    private val idFactory: () -> String,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        store: CollectionStore,
    ) : this(
        store = store,
        idFactory = { UUID.randomUUID().toString() },
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    fun observeCollections(): Flow<List<TsuzukiCollection>> = store.observeCollections()

    suspend fun ensureSystemDefinitions() {
        val now = clock()

        val existingCollection = store.getCollection(SystemDefinitions.COLLECTION_ID)
        val collection = existingCollection ?: TsuzukiCollection(
            id = SystemDefinitions.COLLECTION_ID,
            title = "Discover",
            origin = CollectionOrigin.SYSTEM,
            sortOrder = 0,
            createdAt = now,
            updatedAt = now,
        ).also { store.upsertCollection(it) }

        if (collection.deletedAt != null) return

        val existingFolder = store.getFolder(SystemDefinitions.KITSU_FOLDER_ID)
        val folder = existingFolder ?: CollectionFolder(
            id = SystemDefinitions.KITSU_FOLDER_ID,
            collectionId = collection.id,
            title = "Kitsu",
            origin = CollectionOrigin.SYSTEM,
            sortOrder = 0,
            createdAt = now,
            updatedAt = now,
        ).also { store.upsertFolder(it) }

        if (folder.deletedAt != null) return

        seedSystemList(
            id = SystemDefinitions.KITSU_POPULAR_LIST_ID,
            collectionId = collection.id,
            folderId = folder.id,
            title = "Popular",
            sort = CollectionSortSelection(
                CollectionSortKey.Standard.POPULARITY,
                CollectionSortDirection.DESC,
            ),
            sortOrder = 0,
            now = now,
        )
        seedSystemList(
            id = SystemDefinitions.KITSU_RATED_LIST_ID,
            collectionId = collection.id,
            folderId = folder.id,
            title = "Highest Rated",
            sort = CollectionSortSelection(
                CollectionSortKey.Standard.RATING,
                CollectionSortDirection.DESC,
            ),
            sortOrder = 1,
            now = now,
        )
    }

    suspend fun createUserCollection(
        title: String,
        sortOrder: Long,
    ): TsuzukiCollection {
        val now = clock()
        return TsuzukiCollection(
            id = idFactory(),
            title = title,
            origin = CollectionOrigin.USER,
            sortOrder = sortOrder,
            createdAt = now,
            updatedAt = now,
        ).also { store.upsertCollection(it) }
    }

    suspend fun createUserFolder(
        collectionId: String,
        title: String,
        sortOrder: Long,
        parentFolderId: String? = null,
    ): CollectionFolder {
        val collection = requireNotNull(store.getCollection(collectionId)) {
            "Collection $collectionId does not exist"
        }
        require(collection.deletedAt == null) {
            "Cannot add folders to a deleted Collection"
        }
        require(collection.origin == CollectionOrigin.USER) {
            "System Collections must be duplicated before adding folders"
        }

        if (parentFolderId != null) {
            val parent = requireNotNull(store.getFolder(parentFolderId)) {
                "Parent folder $parentFolderId does not exist"
            }
            require(parent.deletedAt == null && parent.collectionId == collectionId) {
                "Parent folder must be active and belong to Collection $collectionId"
            }
            require(parent.origin == CollectionOrigin.USER) {
                "System folders must be duplicated before adding subfolders"
            }
        }

        val now = clock()
        return CollectionFolder(
            id = idFactory(),
            collectionId = collectionId,
            parentFolderId = parentFolderId,
            title = title,
            origin = CollectionOrigin.USER,
            sortOrder = sortOrder,
            createdAt = now,
            updatedAt = now,
        ).also { store.upsertFolder(it) }
    }

    suspend fun createUserList(
        collectionId: String,
        folderId: String,
        title: String,
        providerId: String,
        query: QueryExpression?,
        sort: CollectionSortSelection,
        sortOrder: Long,
        layoutType: String? = null,
    ): CollectionList {
        val collection = requireNotNull(store.getCollection(collectionId)) {
            "Collection $collectionId does not exist"
        }
        require(collection.deletedAt == null) {
            "Cannot add Lists to a deleted Collection"
        }
        require(collection.origin == CollectionOrigin.USER) {
            "System Collections must be duplicated before adding Lists"
        }
        val folder = requireNotNull(store.getFolder(folderId)) {
            "Folder $folderId does not exist"
        }
        require(folder.deletedAt == null && folder.collectionId == collectionId) {
            "List folder must be active and belong to Collection $collectionId"
        }
        require(folder.origin == CollectionOrigin.USER) {
            "System folders must be duplicated before adding Lists"
        }

        val now = clock()
        return CollectionList(
            id = idFactory(),
            collectionId = collectionId,
            folderId = folderId,
            title = title,
            providerId = providerId,
            query = query,
            sort = sort,
            layoutType = layoutType,
            sortOrder = sortOrder,
            enabled = true,
            origin = CollectionOrigin.USER,
            createdAt = now,
            updatedAt = now,
        ).also { store.upsertList(it) }
    }

    suspend fun updateUserCollection(collection: TsuzukiCollection) {
        require(collection.origin == CollectionOrigin.USER) {
            "System Collections must be duplicated before content edits"
        }
        store.upsertCollection(collection.copy(updatedAt = clock(), revision = collection.revision + 1))
    }

    suspend fun updateUserFolder(folder: CollectionFolder) {
        require(folder.origin == CollectionOrigin.USER) {
            "System folders must be duplicated before content edits"
        }
        store.upsertFolder(folder.copy(updatedAt = clock(), revision = folder.revision + 1))
    }

    suspend fun updateUserList(list: CollectionList) {
        require(list.origin == CollectionOrigin.USER) {
            "System Lists must be duplicated before content edits"
        }
        store.upsertList(list.copy(updatedAt = clock(), revision = list.revision + 1))
    }

    suspend fun reorderUserCollections(
        orderedCollectionIds: List<String>,
    ) {
        val current = store.getCollections()
            .filter { it.origin == CollectionOrigin.USER }
            .sortedWith(compareBy<TsuzukiCollection> { it.sortOrder }.thenBy { it.id })

        require(orderedCollectionIds.size == current.size) {
            "Collection reorder must include every active user Collection exactly once"
        }
        require(orderedCollectionIds.distinct().size == orderedCollectionIds.size) {
            "Collection reorder cannot contain duplicate ids"
        }

        val byId = current.associateBy(TsuzukiCollection::id)
        require(orderedCollectionIds.all(byId::containsKey)) {
            "Collection reorder contains an unknown or non-user Collection"
        }

        val sortOrders = current.map(TsuzukiCollection::sortOrder).sorted()
        val now = clock()
        val updates = orderedCollectionIds.mapIndexedNotNull { index, id ->
            val collection = byId.getValue(id)
            val targetSortOrder = sortOrders[index]
            collection
                .takeIf { it.sortOrder != targetSortOrder }
                ?.copy(
                    sortOrder = targetSortOrder,
                    revision = collection.revision + 1,
                    updatedAt = now,
                )
        }

        if (updates.isNotEmpty()) {
            // Commit the visible order as one batch so nested-content updates never observe a partial reorder.
            store.upsertCollections(updates)
        }
    }

    suspend fun reorderCollection(
        collectionId: String,
        sortOrder: Long,
    ): TsuzukiCollection {
        val existing = requireNotNull(store.getCollection(collectionId)) {
            "Collection $collectionId does not exist"
        }
        return existing.copy(
            sortOrder = sortOrder,
            revision = existing.revision + 1,
            updatedAt = clock(),
        ).also { store.upsertCollection(it) }
    }

    suspend fun reorderUserFolders(
        collectionId: String,
        parentFolderId: String?,
        orderedFolderIds: List<String>,
    ) {
        val current = store.getFolders(collectionId)
            .filter { it.origin == CollectionOrigin.USER && it.parentFolderId == parentFolderId }
            .sortedWith(compareBy<CollectionFolder> { it.sortOrder }.thenBy { it.id })

        require(orderedFolderIds.size == current.size) {
            "Folder reorder must include every active user sibling exactly once"
        }
        require(orderedFolderIds.distinct().size == orderedFolderIds.size) {
            "Folder reorder cannot contain duplicate ids"
        }

        val byId = current.associateBy(CollectionFolder::id)
        require(orderedFolderIds.all(byId::containsKey)) {
            "Folder reorder contains an unknown or non-user Folder"
        }

        val sortOrders = current.map(CollectionFolder::sortOrder).sorted()
        val now = clock()
        orderedFolderIds.forEachIndexed { index, id ->
            val folder = byId.getValue(id)
            val targetSortOrder = sortOrders[index]
            if (folder.sortOrder != targetSortOrder) {
                store.upsertFolder(
                    folder.copy(
                        sortOrder = targetSortOrder,
                        revision = folder.revision + 1,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun reorderFolder(
        folderId: String,
        sortOrder: Long,
    ): CollectionFolder {
        val existing = requireNotNull(store.getFolder(folderId)) {
            "Folder $folderId does not exist"
        }
        return existing.copy(
            sortOrder = sortOrder,
            revision = existing.revision + 1,
            updatedAt = clock(),
        ).also { store.upsertFolder(it) }
    }

    suspend fun reorderUserLists(
        folderId: String,
        orderedListIds: List<String>,
    ) {
        val current = store.getLists(folderId)
            .filter { it.origin == CollectionOrigin.USER }
            .sortedWith(compareBy<CollectionList> { it.sortOrder }.thenBy { it.id })

        require(orderedListIds.size == current.size) {
            "List reorder must include every active user List exactly once"
        }
        require(orderedListIds.distinct().size == orderedListIds.size) {
            "List reorder cannot contain duplicate ids"
        }

        val byId = current.associateBy(CollectionList::id)
        require(orderedListIds.all(byId::containsKey)) {
            "List reorder contains an unknown or non-user List"
        }

        val sortOrders = current.map(CollectionList::sortOrder).sorted()
        val now = clock()
        orderedListIds.forEachIndexed { index, id ->
            val list = byId.getValue(id)
            val targetSortOrder = sortOrders[index]
            if (list.sortOrder != targetSortOrder) {
                store.upsertList(
                    list.copy(
                        sortOrder = targetSortOrder,
                        revision = list.revision + 1,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    suspend fun reorderList(
        listId: String,
        sortOrder: Long,
    ): CollectionList {
        val existing = requireNotNull(store.getList(listId)) {
            "List $listId does not exist"
        }
        return existing.copy(
            sortOrder = sortOrder,
            revision = existing.revision + 1,
            updatedAt = clock(),
        ).also { store.upsertList(it) }
    }

    suspend fun setListEnabled(
        listId: String,
        enabled: Boolean,
    ): CollectionList {
        val existing = requireNotNull(store.getList(listId)) {
            "List $listId does not exist"
        }
        return existing.copy(
            enabled = enabled,
            revision = existing.revision + 1,
            updatedAt = clock(),
        ).also { store.upsertList(it) }
    }

    suspend fun duplicateList(
        listId: String,
        title: String? = null,
    ): CollectionList {
        val source = requireNotNull(store.getList(listId)) {
            "List $listId does not exist"
        }
        require(source.deletedAt == null) { "Deleted Lists cannot be duplicated" }
        val now = clock()
        return source.copy(
            id = idFactory(),
            title = title ?: "${source.title} Copy",
            origin = CollectionOrigin.USER,
            revision = 0,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        ).also { store.upsertList(it) }
    }

    suspend fun duplicateCollection(
        collectionId: String,
        title: String? = null,
    ): TsuzukiCollection {
        val source = requireNotNull(store.getCollection(collectionId)) {
            "Collection $collectionId does not exist"
        }
        require(source.deletedAt == null) { "Deleted Collections cannot be duplicated" }

        val sourceFolders = store.getFolders(collectionId)
        val sourceLists = sourceFolders.flatMap { store.getLists(it.id) }
        val now = clock()
        val newCollectionId = idFactory()
        val newCollection = source.copy(
            id = newCollectionId,
            title = title ?: "${source.title} Copy",
            origin = CollectionOrigin.USER,
            revision = 0,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
        )

        store.upsertCollection(newCollection)

        val folderIdMap = sourceFolders.associate { it.id to idFactory() }
        for (folder in sortFoldersParentFirst(sourceFolders)) {
            store.upsertFolder(
                folder.copy(
                    id = folderIdMap.getValue(folder.id),
                    collectionId = newCollectionId,
                    parentFolderId = folder.parentFolderId?.let { folderIdMap.getValue(it) },
                    origin = CollectionOrigin.USER,
                    revision = 0,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }

        for (list in sourceLists) {
            store.upsertList(
                list.copy(
                    id = idFactory(),
                    collectionId = newCollectionId,
                    folderId = folderIdMap.getValue(list.folderId),
                    origin = CollectionOrigin.USER,
                    revision = 0,
                    createdAt = now,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        }

        return newCollection
    }

    suspend fun tombstoneList(listId: String) {
        val list = requireNotNull(store.getList(listId)) {
            "List $listId does not exist"
        }
        if (list.deletedAt != null) return

        val now = clock()
        store.upsertList(
            list.copy(
                revision = list.revision + 1,
                updatedAt = now,
                deletedAt = now,
            ),
        )
    }

    suspend fun tombstoneFolder(folderId: String) {
        val target = requireNotNull(store.getFolder(folderId)) {
            "Folder $folderId does not exist"
        }
        if (target.deletedAt != null) return

        val folders = store.getFolders(target.collectionId)
        val affectedIds = descendantFolderIds(target.id, folders)
        val lists = affectedIds.flatMap { store.getLists(it) }
        val now = clock()

        lists.forEach { list ->
            store.upsertList(
                list.copy(
                    revision = list.revision + 1,
                    updatedAt = now,
                    deletedAt = now,
                ),
            )
        }

        folders
            .filter { it.id in affectedIds }
            .sortedByDescending { folderDepth(it, folders.associateBy(CollectionFolder::id)) }
            .forEach { folder ->
                store.upsertFolder(
                    folder.copy(
                        revision = folder.revision + 1,
                        updatedAt = now,
                        deletedAt = now,
                    ),
                )
            }
    }

    suspend fun tombstoneCollection(collectionId: String) {
        val collection = requireNotNull(store.getCollection(collectionId)) {
            "Collection $collectionId does not exist"
        }
        if (collection.deletedAt != null) return

        val folders = store.getFolders(collectionId)
        val lists = folders.flatMap { store.getLists(it.id) }
        val now = clock()

        lists.forEach { list ->
            store.upsertList(
                list.copy(
                    revision = list.revision + 1,
                    updatedAt = now,
                    deletedAt = now,
                ),
            )
        }
        folders
            .sortedByDescending { folderDepth(it, folders.associateBy(CollectionFolder::id)) }
            .forEach { folder ->
                store.upsertFolder(
                    folder.copy(
                        revision = folder.revision + 1,
                        updatedAt = now,
                        deletedAt = now,
                    ),
                )
            }

        store.upsertCollection(
            collection.copy(
                revision = collection.revision + 1,
                updatedAt = now,
                deletedAt = now,
            ),
        )
    }

    private suspend fun seedSystemList(
        id: String,
        collectionId: String,
        folderId: String,
        title: String,
        sort: CollectionSortSelection,
        sortOrder: Long,
        now: Long,
    ) {
        if (store.getList(id) != null) return

        store.upsertList(
            CollectionList(
                id = id,
                collectionId = collectionId,
                folderId = folderId,
                title = title,
                providerId = "kitsu",
                query = null,
                sort = sort,
                sortOrder = sortOrder,
                enabled = true,
                origin = CollectionOrigin.SYSTEM,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    private fun descendantFolderIds(
        rootId: String,
        folders: List<CollectionFolder>,
    ): Set<String> {
        val result = linkedSetOf(rootId)
        var changed = true
        while (changed) {
            changed = false
            for (folder in folders) {
                if (folder.parentFolderId in result && result.add(folder.id)) {
                    changed = true
                }
            }
        }
        return result
    }

    private fun sortFoldersParentFirst(folders: List<CollectionFolder>): List<CollectionFolder> {
        val byId = folders.associateBy(CollectionFolder::id)
        return folders.sortedBy { folderDepth(it, byId) }
    }

    private fun folderDepth(
        folder: CollectionFolder,
        byId: Map<String, CollectionFolder>,
        path: Set<String> = emptySet(),
    ): Int {
        require(folder.id !in path) {
            "Collection folder hierarchy contains a cycle at ${folder.id}"
        }
        val parentId = folder.parentFolderId ?: return 0
        val parent = requireNotNull(byId[parentId]) {
            "Folder ${folder.id} references missing active parent $parentId"
        }
        return 1 + folderDepth(parent, byId, path + folder.id)
    }

    object SystemDefinitions {
        const val COLLECTION_ID: String = "system:discover"
        const val KITSU_FOLDER_ID: String = "system:discover:kitsu"
        const val KITSU_POPULAR_LIST_ID: String = "system:discover:kitsu:popular"
        const val KITSU_RATED_LIST_ID: String = "system:discover:kitsu:highest-rated"
    }
}
