package tachiyomi.domain.tsuzuki.collections.interactor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore

class ManageCollectionDefinitionsTest {

    @Test
    fun `system definitions seed idempotently without overwriting user state`() = runTest {
        val store = FakeStore()
        val manager = manager(store)

        manager.ensureSystemDefinitions()

        store.collectionUpserts shouldBe 1
        store.folderUpserts shouldBe 1
        store.listUpserts shouldBe 2

        manager.setListEnabled(
            ManageCollectionDefinitions.SystemDefinitions.KITSU_POPULAR_LIST_ID,
            enabled = false,
        )
        manager.reorderList(
            ManageCollectionDefinitions.SystemDefinitions.KITSU_POPULAR_LIST_ID,
            sortOrder = 99,
        )
        val upsertsAfterCustomization = store.listUpserts

        manager.ensureSystemDefinitions()

        store.collectionUpserts shouldBe 1
        store.folderUpserts shouldBe 1
        store.listUpserts shouldBe upsertsAfterCustomization

        val popular = store.getList(
            ManageCollectionDefinitions.SystemDefinitions.KITSU_POPULAR_LIST_ID,
        )!!
        popular.enabled shouldBe false
        popular.sortOrder shouldBe 99
        popular.origin shouldBe CollectionOrigin.SYSTEM
    }

    @Test
    fun `deleted system list is not resurrected by seeding`() = runTest {
        val store = FakeStore()
        val manager = manager(store)

        manager.ensureSystemDefinitions()
        manager.tombstoneList(
            ManageCollectionDefinitions.SystemDefinitions.KITSU_POPULAR_LIST_ID,
        )

        val tombstoned = store.getList(
            ManageCollectionDefinitions.SystemDefinitions.KITSU_POPULAR_LIST_ID,
        )!!
        tombstoned.deletedAt shouldBe 1_000

        val upsertsBeforeReseed = store.listUpserts
        manager.ensureSystemDefinitions()

        store.listUpserts shouldBe upsertsBeforeReseed
        store.getList(
            ManageCollectionDefinitions.SystemDefinitions.KITSU_POPULAR_LIST_ID,
        )!!.deletedAt shouldBe 1_000
    }

    @Test
    fun `duplicating system list produces independent user owned copy`() = runTest {
        val store = FakeStore()
        val manager = manager(store, ids = ArrayDeque(listOf("copy-list")))

        manager.ensureSystemDefinitions()

        val duplicate = manager.duplicateList(
            ManageCollectionDefinitions.SystemDefinitions.KITSU_RATED_LIST_ID,
        )

        duplicate.id shouldBe "copy-list"
        duplicate.origin shouldBe CollectionOrigin.USER
        duplicate.title shouldBe "Highest Rated Copy"
        duplicate.sort shouldBe CatalogSort.RATING_DESC
        duplicate.deletedAt shouldBe null

        val original = store.getList(
            ManageCollectionDefinitions.SystemDefinitions.KITSU_RATED_LIST_ID,
        )!!
        original.origin shouldBe CollectionOrigin.SYSTEM
        original.id shouldBe ManageCollectionDefinitions.SystemDefinitions.KITSU_RATED_LIST_ID
    }

    @Test
    fun `duplicating collection remaps nested folders and makes whole graph user owned`() = runTest {
        val store = FakeStore()
        val ids = ArrayDeque(
            listOf(
                "copy-collection",
                "copy-root",
                "copy-child",
                "copy-list-a",
                "copy-list-b",
            ),
        )
        val manager = manager(store, ids)

        val source = collection("source", origin = CollectionOrigin.SYSTEM)
        val root = folder("root", source.id, parent = null, origin = CollectionOrigin.SYSTEM)
        val child = folder("child", source.id, parent = root.id, origin = CollectionOrigin.SYSTEM)
        val query = QueryExpression.Predicate(
            QueryField.STATUS,
            QueryOperator.EQUALS,
            QueryValue.of("ongoing"),
        )
        val rootList = list(
            id = "list-a",
            collectionId = source.id,
            folderId = root.id,
            origin = CollectionOrigin.SYSTEM,
            query = query,
        )
        val childList = list(
            id = "list-b",
            collectionId = source.id,
            folderId = child.id,
            origin = CollectionOrigin.SYSTEM,
            query = null,
        )

        store.upsertCollection(source)
        store.upsertFolder(root)
        store.upsertFolder(child)
        store.upsertList(rootList)
        store.upsertList(childList)

        val duplicate = manager.duplicateCollection(source.id)

        duplicate.id shouldBe "copy-collection"
        duplicate.origin shouldBe CollectionOrigin.USER

        val folders = store.getFolders(duplicate.id)
        folders.map { it.id }.sorted() shouldContainExactly listOf("copy-child", "copy-root")
        folders.all { it.origin == CollectionOrigin.USER } shouldBe true

        val copiedRoot = folders.first { it.parentFolderId == null }
        val copiedChild = folders.first { it.parentFolderId != null }
        copiedChild.parentFolderId shouldBe copiedRoot.id

        val copiedLists = folders.flatMap { store.getLists(it.id) }
        copiedLists.size shouldBe 2
        copiedLists.all { it.origin == CollectionOrigin.USER } shouldBe true
        copiedLists.first { it.query != null }.query shouldBe query
        copiedLists.all { it.collectionId == duplicate.id } shouldBe true
    }

    @Test
    fun `tombstoning folder cascades only through its subtree`() = runTest {
        val store = FakeStore()
        val manager = manager(store)
        val collection = collection("collection")
        val root = folder("root", collection.id)
        val child = folder("child", collection.id, parent = root.id)
        val sibling = folder("sibling", collection.id)
        val rootList = list("root-list", collection.id, root.id)
        val childList = list("child-list", collection.id, child.id)
        val siblingList = list("sibling-list", collection.id, sibling.id)

        store.upsertCollection(collection)
        listOf(root, child, sibling).forEach { store.upsertFolder(it) }
        listOf(rootList, childList, siblingList).forEach { store.upsertList(it) }

        manager.tombstoneFolder(root.id)

        store.getFolder(root.id)!!.deletedAt shouldBe 1_000
        store.getFolder(child.id)!!.deletedAt shouldBe 1_000
        store.getList(rootList.id)!!.deletedAt shouldBe 1_000
        store.getList(childList.id)!!.deletedAt shouldBe 1_000

        store.getFolder(sibling.id)!!.deletedAt shouldBe null
        store.getList(siblingList.id)!!.deletedAt shouldBe null
    }

    @Test
    fun `tombstoning collection tombstones all active children`() = runTest {
        val store = FakeStore()
        val manager = manager(store)
        val collection = collection("collection")
        val root = folder("root", collection.id)
        val child = folder("child", collection.id, parent = root.id)
        val first = list("first", collection.id, root.id)
        val second = list("second", collection.id, child.id)

        store.upsertCollection(collection)
        store.upsertFolder(root)
        store.upsertFolder(child)
        store.upsertList(first)
        store.upsertList(second)

        manager.tombstoneCollection(collection.id)

        store.getCollection(collection.id)!!.deletedAt shouldBe 1_000
        store.getFolder(root.id)!!.deletedAt shouldBe 1_000
        store.getFolder(child.id)!!.deletedAt shouldBe 1_000
        store.getList(first.id)!!.deletedAt shouldBe 1_000
        store.getList(second.id)!!.deletedAt shouldBe 1_000
    }

    @Test
    fun `system content cannot be edited in place but may be reordered`() = runTest {
        val store = FakeStore()
        val manager = manager(store)
        manager.ensureSystemDefinitions()

        val systemCollection = store.getCollection(
            ManageCollectionDefinitions.SystemDefinitions.COLLECTION_ID,
        )!!

        shouldThrow<IllegalArgumentException> {
            manager.updateUserCollection(systemCollection.copy(title = "Changed"))
        }

        val reordered = manager.reorderCollection(systemCollection.id, sortOrder = 5)
        reordered.sortOrder shouldBe 5
        reordered.origin shouldBe CollectionOrigin.SYSTEM
    }

    private fun manager(
        store: FakeStore,
        ids: ArrayDeque<String> = ArrayDeque(),
    ): ManageCollectionDefinitions {
        return ManageCollectionDefinitions(
            store = store,
            idFactory = {
                check(ids.isNotEmpty()) { "Test id queue exhausted" }
                ids.removeFirst()
            },
            clock = { 1_000L },
        )
    }

    private fun collection(
        id: String,
        origin: CollectionOrigin = CollectionOrigin.USER,
    ) = TsuzukiCollection(
        id = id,
        title = "Collection $id",
        origin = origin,
        sortOrder = 0,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun folder(
        id: String,
        collectionId: String,
        parent: String? = null,
        origin: CollectionOrigin = CollectionOrigin.USER,
    ) = CollectionFolder(
        id = id,
        collectionId = collectionId,
        parentFolderId = parent,
        title = "Folder $id",
        origin = origin,
        sortOrder = 0,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun list(
        id: String,
        collectionId: String,
        folderId: String,
        origin: CollectionOrigin = CollectionOrigin.USER,
        query: QueryExpression? = null,
    ) = CollectionList(
        id = id,
        collectionId = collectionId,
        folderId = folderId,
        title = "List $id",
        providerId = "kitsu",
        query = query,
        sort = CatalogSort.POPULARITY_DESC,
        sortOrder = 0,
        origin = origin,
        createdAt = 1,
        updatedAt = 1,
    )

    private class FakeStore : CollectionStore {
        private val collections = linkedMapOf<String, TsuzukiCollection>()
        private val folders = linkedMapOf<String, CollectionFolder>()
        private val lists = linkedMapOf<String, CollectionList>()

        var collectionUpserts: Int = 0
        var folderUpserts: Int = 0
        var listUpserts: Int = 0

        override suspend fun getCollection(id: String): TsuzukiCollection? = collections[id]

        override suspend fun getCollections(includeDeleted: Boolean): List<TsuzukiCollection> {
            return collections.values
                .filter { includeDeleted || it.deletedAt == null }
                .sortedBy { it.sortOrder }
        }

        override fun observeCollections(): Flow<List<TsuzukiCollection>> {
            return flowOf(collections.values.filter { it.deletedAt == null })
        }

        override suspend fun upsertCollection(collection: TsuzukiCollection) {
            collectionUpserts++
            collections[collection.id] = collection
        }

        override suspend fun getFolder(id: String): CollectionFolder? = folders[id]

        override suspend fun getFolders(
            collectionId: String,
            includeDeleted: Boolean,
        ): List<CollectionFolder> {
            return folders.values
                .filter { it.collectionId == collectionId }
                .filter { includeDeleted || it.deletedAt == null }
                .sortedBy { it.sortOrder }
        }

        override fun observeFolders(collectionId: String): Flow<List<CollectionFolder>> {
            return flowOf(
                folders.values.filter {
                    it.collectionId == collectionId && it.deletedAt == null
                },
            )
        }

        override suspend fun upsertFolder(folder: CollectionFolder) {
            folderUpserts++
            folders[folder.id] = folder
        }

        override suspend fun getList(id: String): CollectionList? = lists[id]

        override suspend fun getLists(
            folderId: String,
            includeDeleted: Boolean,
        ): List<CollectionList> {
            return lists.values
                .filter { it.folderId == folderId }
                .filter { includeDeleted || it.deletedAt == null }
                .sortedBy { it.sortOrder }
        }

        override fun observeLists(folderId: String): Flow<List<CollectionList>> {
            return flowOf(
                lists.values.filter {
                    it.folderId == folderId && it.deletedAt == null
                },
            )
        }

        override suspend fun upsertList(list: CollectionList) {
            listUpserts++
            lists[list.id] = list
        }
    }
}
