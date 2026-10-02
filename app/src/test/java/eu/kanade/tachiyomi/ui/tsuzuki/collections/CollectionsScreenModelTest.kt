package eu.kanade.tachiyomi.ui.tsuzuki.collections

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingCapability
import tachiyomi.domain.tsuzuki.collections.capability.CollectionPagingMode
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderDescriptor
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderScope
import tachiyomi.domain.tsuzuki.collections.capability.FilterOption
import tachiyomi.domain.tsuzuki.collections.execution.CollectionQueryProviderRegistry
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionDraft
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionDraftRequest
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionDraftResult
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionList
import tachiyomi.domain.tsuzuki.collections.execution.LogicalCatalogPage
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
import tachiyomi.domain.tsuzuki.collections.query.QueryValue
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
    fun `screen state exposes registered Collection provider descriptors`() = runTest(dispatcher) {
        val store = mockk<CollectionStore>()
        every { store.observeCollections() } returns flowOf(emptyList())
        val descriptors = listOf(
            descriptor("kitsu", "Kitsu"),
            descriptor("mal", "MyAnimeList"),
            descriptor("mangaupdates", "MangaUpdates"),
            descriptor("bangumi", "Bangumi"),
            descriptor("shikimori", "Shikimori"),
            descriptor("hikka", "Hikka"),
        )
        val registry = mockk<CollectionQueryProviderRegistry>()
        every { registry.descriptors() } returns descriptors
        every { registry.all() } returns emptyList()

        val model = CollectionsScreenModel(
            store = store,
            manager = mockk(),
            exportCollections = mockk(),
            importCollections = mockk(),
            executeCollectionList = mockk(),
            executeCollectionDraft = mockk(),
            providerRegistry = registry,
        )
        advanceUntilIdle()

        val ready = model.state.value as CollectionsScreenState.Ready
        ready.providerDescriptors shouldBe descriptors
    }

    @Test
    fun `filter lookup debounces and cancels stale provider query`() = runTest(dispatcher) {
        val store = mockk<CollectionStore>()
        every { store.observeCollections() } returns flowOf(emptyList())
        val registry = mockk<CollectionQueryProviderRegistry>()
        every { registry.descriptors() } returns listOf(descriptor("shikimori", "Shikimori"))
        every { registry.all() } returns emptyList()
        coEvery {
            registry.lookupValues(
                providerId = "shikimori",
                lookupId = "shikimori.publishers",
                query = "shou",
            )
        } returns Result.success(
            listOf(FilterOption("1", "Shueisha", QueryValue.of("1"))),
        )

        val model = CollectionsScreenModel(
            store = store,
            manager = mockk(),
            exportCollections = mockk(),
            importCollections = mockk(),
            executeCollectionList = mockk(),
            executeCollectionDraft = mockk(),
            providerRegistry = registry,
        )
        advanceUntilIdle()

        model.dispatch(
            CollectionsAction.FilterLookupRequested(
                providerId = "shikimori",
                lookupId = "shikimori.publishers",
                query = "sh",
            ),
        )
        advanceTimeBy(100)
        model.dispatch(
            CollectionsAction.FilterLookupRequested(
                providerId = "shikimori",
                lookupId = "shikimori.publishers",
                query = "shou",
            ),
        )
        advanceTimeBy(249)

        coVerify(exactly = 0) {
            registry.lookupValues("shikimori", "shikimori.publishers", any())
        }

        advanceTimeBy(1)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            registry.lookupValues("shikimori", "shikimori.publishers", "sh")
        }
        coVerify(exactly = 1) {
            registry.lookupValues("shikimori", "shikimori.publishers", "shou")
        }
        val ready = model.state.value as CollectionsScreenState.Ready
        ready.filterLookupStates[
            CollectionFilterLookupKey("shikimori", "shikimori.publishers")
        ] shouldBe CollectionFilterLookupState.Ready(
            query = "shou",
            options = listOf(FilterOption("1", "Shueisha", QueryValue.of("1"))),
        )
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
            executeCollectionDraft = mockk<ExecuteCollectionDraft>(),
            providerRegistry = mockRegistry(),
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
            executeCollectionDraft = mockk<ExecuteCollectionDraft>(),
            providerRegistry = mockRegistry(),
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

    @Test
    fun `draft preview debounces edits and cancels stale draft before execution`() = runTest(dispatcher) {
        val store = mockk<CollectionStore>()
        every { store.observeCollections() } returns flowOf(emptyList())
        val executeDraft = mockk<ExecuteCollectionDraft>()
        coEvery { executeDraft.execute(any()) } returns ExecuteCollectionDraftResult.Page(
            LogicalCatalogPage(
                items = listOf(
                    CatalogItem(
                        provider = "kitsu",
                        providerId = "1",
                        title = "Preview",
                    ),
                ),
                nextCursor = null,
            ),
        )

        val model = CollectionsScreenModel(
            store = store,
            manager = mockk(),
            exportCollections = mockk(),
            importCollections = mockk(),
            executeCollectionList = mockk(),
            executeCollectionDraft = executeDraft,
            providerRegistry = mockRegistry(),
        )
        advanceUntilIdle()

        val first = CollectionListDraft(
            title = "First",
            query = null,
            sort = CollectionSortSelection.DEFAULT,
            layoutType = null,
            providerId = "kitsu",
        )
        val second = first.copy(title = "Second")

        model.dispatch(CollectionsAction.PreviewDraftChanged(first))
        advanceTimeBy(100)
        model.dispatch(CollectionsAction.PreviewDraftChanged(second))
        advanceTimeBy(299)

        coVerify(exactly = 0) { executeDraft.execute(any()) }

        advanceTimeBy(1)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            executeDraft.execute(
                match<ExecuteCollectionDraftRequest> { request ->
                    request.draft.providerId == "kitsu" &&
                        request.draft.sort == CollectionSortSelection.DEFAULT
                },
            )
        }
        val ready = model.state.value as CollectionsScreenState.Ready
        val preview = ready.draftPreviewState as CollectionDraftPreviewState.Content
        preview.items.map { it.providerId } shouldBe listOf("1")
    }

    @Test
    fun `clearing draft preview cancels pending work and returns idle`() = runTest(dispatcher) {
        val store = mockk<CollectionStore>()
        every { store.observeCollections() } returns flowOf(emptyList())
        val executeDraft = mockk<ExecuteCollectionDraft>()

        val model = CollectionsScreenModel(
            store = store,
            manager = mockk(),
            exportCollections = mockk(),
            importCollections = mockk(),
            executeCollectionList = mockk(),
            executeCollectionDraft = executeDraft,
            providerRegistry = mockRegistry(),
        )
        advanceUntilIdle()

        model.dispatch(
            CollectionsAction.PreviewDraftChanged(
                CollectionListDraft(
                    title = "Draft",
                    query = null,
                    sort = CollectionSortSelection.DEFAULT,
                    layoutType = null,
                ),
            ),
        )
        advanceTimeBy(100)
        model.dispatch(CollectionsAction.PreviewDraftChanged(null))
        advanceUntilIdle()

        coVerify(exactly = 0) { executeDraft.execute(any()) }
        val ready = model.state.value as CollectionsScreenState.Ready
        ready.draftPreviewState shouldBe CollectionDraftPreviewState.Idle
    }

    private fun mockRegistry(): CollectionQueryProviderRegistry {
        val registry = mockk<CollectionQueryProviderRegistry>()
        every { registry.descriptors() } returns listOf(descriptor("kitsu", "Kitsu"))
        every { registry.all() } returns emptyList()
        return registry
    }

    private fun descriptor(
        providerId: String,
        displayName: String,
    ) = CollectionProviderDescriptor(
        providerId = providerId,
        displayName = displayName,
        scope = CollectionProviderScope.GLOBAL,
        filters = emptyList(),
        sorts = emptyList(),
        paging = CollectionPagingCapability(
            mode = CollectionPagingMode.OFFSET,
            maxPageSize = 20,
        ),
    )

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
