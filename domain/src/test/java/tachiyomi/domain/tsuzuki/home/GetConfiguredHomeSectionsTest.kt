package tachiyomi.domain.tsuzuki.home

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.home.interactor.GetConfiguredHomeSections
import tachiyomi.domain.tsuzuki.home.interactor.HomeCollectionListLoader
import tachiyomi.domain.tsuzuki.home.model.HomeRowContent
import tachiyomi.domain.tsuzuki.home.model.HomeSection

class GetConfiguredHomeSectionsTest {

    @Test
    fun `home has no discovery rows when user has no collections`() = runTest {
        val store = FakeCollectionStore()
        val calls = mutableListOf<String>()
        val interactor = GetConfiguredHomeSections(
            store = store,
            loader = HomeCollectionListLoader { listId, _ ->
                calls += listId
                HomeRowContent.Content(emptyList())
            },
        )

        interactor.execute() shouldBe emptyList()
        calls shouldBe emptyList()
    }

    @Test
    fun `system collection does not become default Home discovery`() = runTest {
        val store = FakeCollectionStore().apply {
            addGraph(origin = CollectionOrigin.SYSTEM)
        }
        val calls = mutableListOf<String>()
        val interactor = GetConfiguredHomeSections(
            store = store,
            loader = HomeCollectionListLoader { listId, _ ->
                calls += listId
                HomeRowContent.Content(emptyList())
            },
        )

        interactor.execute() shouldBe emptyList()
        calls shouldBe emptyList()
    }

    @Test
    fun `user collection loads one bounded compact preview from the first enabled list`() = runTest {
        val store = FakeCollectionStore().apply {
            addGraph(origin = CollectionOrigin.USER)
            lists["folder-1"] = listOf(
                list(id = "disabled", enabled = false, sortOrder = 0),
                list(id = "enabled", enabled = true, sortOrder = 1),
                list(id = "later", enabled = true, sortOrder = 2),
            )
        }
        val calls = mutableListOf<Pair<String, Int>>()
        val interactor = GetConfiguredHomeSections(
            store = store,
            loader = HomeCollectionListLoader { listId, pageSize ->
                calls += listId to pageSize
                HomeRowContent.Content(
                    listOf(
                        CatalogItem(
                            provider = "kitsu",
                            providerId = "preview-1",
                            title = "Preview",
                            coverUrl = "https://example/preview.jpg",
                        ),
                    ),
                )
            },
        )

        val sections = interactor.execute(pageSize = 4)

        sections shouldBe listOf(
            HomeSection.CollectionSection(
                collectionId = "collection-1",
                title = "My Home",
                previewItems = listOf(
                    CatalogItem(
                        provider = "kitsu",
                        providerId = "preview-1",
                        title = "Preview",
                        coverUrl = "https://example/preview.jpg",
                    ),
                ),
            ),
        )
        calls shouldBe listOf("enabled" to 4)
    }

    @Test
    fun `collection browse exposes root folders as independent compact preview sections`() = runTest {
        val store = FakeCollectionStore().apply {
            addGraph(origin = CollectionOrigin.USER)
            addRootFolder(
                id = "folder-2",
                title = "Second",
                sortOrder = 1,
                listsForFolder = listOf(list(id = "second-list", enabled = true, sortOrder = 0, folderId = "folder-2")),
            )
        }
        val calls = mutableListOf<String>()
        val interactor = GetConfiguredHomeSections(
            store = store,
            loader = HomeCollectionListLoader { listId, _ ->
                calls += listId
                HomeRowContent.Content(
                    listOf(
                        CatalogItem(
                            provider = "kitsu",
                            providerId = listId,
                            title = listId,
                        ),
                    ),
                )
            },
        )

        val browse = interactor.subscribeCollection("collection-1", pageSize = 4).first()

        browse?.collectionId shouldBe "collection-1"
        browse?.title shouldBe "My Home"
        browse?.folders?.map { it.folderId to it.title } shouldBe listOf(
            "folder-1" to "Rows",
            "folder-2" to "Second",
        )
        browse?.folders?.map { it.previewItems.map(CatalogItem::providerId) } shouldBe listOf(
            listOf("list-1"),
            listOf("second-list"),
        )
        calls shouldBe listOf("list-1", "second-list")
    }

    private class FakeCollectionStore : CollectionStore {
        private val collections = mutableListOf<TsuzukiCollection>()
        private val collectionFlow = MutableStateFlow<List<TsuzukiCollection>>(emptyList())
        private val folders = mutableMapOf<String, List<CollectionFolder>>()
        val lists = mutableMapOf<String, List<CollectionList>>()

        fun addRootFolder(
            id: String,
            title: String,
            sortOrder: Long,
            listsForFolder: List<CollectionList>,
        ) {
            val collection = collections.single()
            val folder = CollectionFolder(
                id = id,
                collectionId = collection.id,
                title = title,
                origin = collection.origin,
                sortOrder = sortOrder,
                createdAt = 1,
                updatedAt = 1,
            )
            folders[collection.id] = folders[collection.id].orEmpty() + folder
            lists[id] = listsForFolder
        }

        fun addGraph(origin: CollectionOrigin) {
            val collection = TsuzukiCollection(
                id = "collection-1",
                title = "My Home",
                origin = origin,
                sortOrder = 0,
                createdAt = 1,
                updatedAt = 1,
            )
            val folder = CollectionFolder(
                id = "folder-1",
                collectionId = collection.id,
                title = "Rows",
                origin = origin,
                sortOrder = 0,
                createdAt = 1,
                updatedAt = 1,
            )
            collections += collection
            collectionFlow.value = collections.toList()
            folders[collection.id] = listOf(folder)
            lists[folder.id] = listOf(list("list-1", true, 0))
        }

        override suspend fun getCollection(id: String) =
            collections.firstOrNull { it.id == id }

        override suspend fun getCollections(includeDeleted: Boolean) =
            collections.filter { includeDeleted || it.deletedAt == null }

        override fun observeCollections(): Flow<List<TsuzukiCollection>> = collectionFlow

        override suspend fun upsertCollection(collection: TsuzukiCollection) {
            collections.removeAll { it.id == collection.id }
            collections += collection
            collectionFlow.value = collections.toList()
        }

        override suspend fun getFolder(id: String) =
            folders.values.flatten().firstOrNull { it.id == id }

        override suspend fun getFolders(collectionId: String, includeDeleted: Boolean) =
            folders[collectionId].orEmpty().filter { includeDeleted || it.deletedAt == null }

        override fun observeFolders(collectionId: String): Flow<List<CollectionFolder>> =
            MutableStateFlow(folders[collectionId].orEmpty())

        override suspend fun upsertFolder(folder: CollectionFolder) {
            folders[folder.collectionId] =
                folders[folder.collectionId].orEmpty().filterNot { it.id == folder.id } + folder
        }

        override suspend fun getList(id: String) =
            lists.values.flatten().firstOrNull { it.id == id }

        override suspend fun getLists(folderId: String, includeDeleted: Boolean) =
            lists[folderId].orEmpty().filter { includeDeleted || it.deletedAt == null }

        override fun observeLists(folderId: String): Flow<List<CollectionList>> =
            MutableStateFlow(lists[folderId].orEmpty())

        override suspend fun upsertList(list: CollectionList) {
            lists[list.folderId] =
                lists[list.folderId].orEmpty().filterNot { it.id == list.id } + list
        }
    }
}

private fun list(
    id: String,
    enabled: Boolean,
    sortOrder: Long,
    folderId: String = "folder-1",
) = CollectionList(
    id = id,
    collectionId = "collection-1",
    folderId = folderId,
    title = id,
    providerId = "kitsu",
    query = null,
    sort = CollectionSortSelection.DEFAULT,
    sortOrder = sortOrder,
    enabled = enabled,
    origin = CollectionOrigin.USER,
    createdAt = 1,
    updatedAt = 1,
)
