package eu.kanade.tachiyomi.ui.tsuzuki.collections

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionList
import tachiyomi.domain.tsuzuki.collections.interactor.ExportCollections
import tachiyomi.domain.tsuzuki.collections.interactor.ImportCollections
import tachiyomi.domain.tsuzuki.collections.interactor.ManageCollectionDefinitions
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortDirection
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortKey
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionsScreenModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `new collection persists draft folders and lists in their editor order`() = runTest(dispatcher) {
        val store = mockk<CollectionStore>()
        every { store.observeCollections() } returns flowOf(emptyList())

        val manager = mockk<ManageCollectionDefinitions>()
        val createdCollection = collection(id = "created", sortOrder = 0)
        val createdFolder = CollectionFolder(
            id = "folder",
            collectionId = createdCollection.id,
            parentFolderId = null,
            title = "Trending",
            origin = CollectionOrigin.USER,
            sortOrder = 0,
            createdAt = 1L,
            updatedAt = 1L,
        )
        val createdList = CollectionList(
            id = "list",
            collectionId = createdCollection.id,
            folderId = createdFolder.id,
            title = "Top rated",
            providerId = "mangaupdates",
            query = null,
            sort = CollectionSortSelection(
                CollectionSortKey.Standard.RATING,
                CollectionSortDirection.DESC,
            ),
            layoutType = "list",
            sortOrder = 0,
            origin = CollectionOrigin.USER,
            createdAt = 1L,
            updatedAt = 1L,
        )

        coEvery {
            manager.createUserCollection(
                title = "Discover",
                sortOrder = 0,
            )
        } returns createdCollection
        coEvery {
            manager.createUserFolder(
                collectionId = createdCollection.id,
                title = "Trending",
                sortOrder = 0,
                parentFolderId = null,
            )
        } returns createdFolder
        coEvery {
            manager.createUserList(
                collectionId = createdCollection.id,
                folderId = createdFolder.id,
                title = "Top rated",
                providerId = "mangaupdates",
                query = null,
                sort = CollectionSortSelection(
                CollectionSortKey.Standard.RATING,
                CollectionSortDirection.DESC,
            ),
                sortOrder = 0,
                layoutType = "list",
            )
        } returns createdList

        val model = CollectionsScreenModel(
            store = store,
            manager = manager,
            exportCollections = mockk(),
            importCollections = mockk(),
            executeCollectionList = mockk<ExecuteCollectionList>(),
        )
        advanceUntilIdle()

        model.dispatch(
            CollectionsAction.CreateCollection(
                title = "Discover",
                folders = listOf(
                    CollectionFolderDraft(
                        title = "Trending",
                        lists = listOf(
                            CollectionListDraft(
                                title = "Top rated",
                                query = null,
                                sort = CollectionSortSelection(
                CollectionSortKey.Standard.RATING,
                CollectionSortDirection.DESC,
            ),
                                layoutType = "list",
                                providerId = "mangaupdates",
                            ),
                        ),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            manager.createUserFolder(
                collectionId = createdCollection.id,
                title = "Trending",
                sortOrder = 0,
                parentFolderId = null,
            )
        }
        coVerify(exactly = 1) {
            manager.createUserList(
                collectionId = createdCollection.id,
                folderId = createdFolder.id,
                title = "Top rated",
                providerId = "mangaupdates",
                query = null,
                sort = CollectionSortSelection(
                CollectionSortKey.Standard.RATING,
                CollectionSortDirection.DESC,
            ),
                sortOrder = 0,
                layoutType = "list",
            )
        }
    }

    @Test
    fun `drag reorder persists the exact displayed collection order`() = runTest(dispatcher) {
        val first = collection(id = "first", sortOrder = 10)
        val second = collection(id = "second", sortOrder = 20)
        val third = collection(id = "third", sortOrder = 30)
        val source = listOf(first, second, third)

        val store = mockk<CollectionStore>()
        every { store.observeCollections() } returns flowOf(source)
        every { store.observeFolders(any()) } returns flowOf(emptyList())

        val manager = mockk<ManageCollectionDefinitions>()
        coEvery { manager.reorderUserCollections(any()) } returns Unit

        val model = CollectionsScreenModel(
            store = store,
            manager = manager,
            exportCollections = mockk(),
            importCollections = mockk(),
            executeCollectionList = mockk<ExecuteCollectionList>(),
        )
        advanceUntilIdle()

        model.dispatch(
            CollectionsAction.ReorderCollections(
                orderedCollectionIds = listOf("third", "first", "second"),
            ),
        )
        advanceUntilIdle()

        coVerify(exactly = 1) {
            manager.reorderUserCollections(listOf("third", "first", "second"))
        }
    }

    private fun collection(
        id: String,
        sortOrder: Long,
    ) = TsuzukiCollection(
        id = id,
        title = id,
        origin = CollectionOrigin.USER,
        sortOrder = sortOrder,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
