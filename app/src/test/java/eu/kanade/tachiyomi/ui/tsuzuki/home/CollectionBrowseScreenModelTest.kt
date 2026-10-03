package eu.kanade.tachiyomi.ui.tsuzuki.home

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionList
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListRequest
import tachiyomi.domain.tsuzuki.collections.execution.ExecuteCollectionListResult
import tachiyomi.domain.tsuzuki.collections.execution.LogicalCatalogPage
import tachiyomi.domain.tsuzuki.collections.execution.ResidualPageCursor
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.home.interactor.GetConfiguredHomeSections
import tachiyomi.domain.tsuzuki.home.model.HomeCollectionBrowse
import tachiyomi.domain.tsuzuki.home.model.HomeFolderBrowse
import tachiyomi.domain.tsuzuki.home.model.HomeFolderPreview
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitleFromCatalog
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalTitle

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionBrowseScreenModelTest {

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
    fun `collection browse binds approved folder preview projection`() = runTest(dispatcher) {
        val interactor = mockk<GetConfiguredHomeSections>()
        val projection = HomeCollectionBrowse(
            collectionId = "collection-1",
            title = "Minha coleção",
            folders = listOf(
                HomeFolderPreview(
                    folderId = "folder-1",
                    title = "Romance",
                    previewItems = listOf(item("preview-1")),
                ),
            ),
        )
        every { interactor.subscribeCollection("collection-1", 4) } returns
            MutableStateFlow(projection)

        val model = CollectionBrowseScreenModel(interactor)
        model.bind("collection-1")
        advanceUntilIdle()

        model.state.value shouldBe CollectionBrowseScreenState.Ready(projection)
    }

    @Test
    fun `folder browse selects first enabled list and loads real catalog page`() = runTest(dispatcher) {
        val interactor = mockk<GetConfiguredHomeSections>()
        val disabled = list("disabled", enabled = false, sortOrder = 0)
        val first = list("first", enabled = true, sortOrder = 1)
        val second = list("second", enabled = true, sortOrder = 2)
        every { interactor.subscribeFolder("collection-1", "folder-1", 4) } returns
            MutableStateFlow(
                HomeFolderBrowse(
                    collectionId = "collection-1",
                    folderId = "folder-1",
                    title = "Romance",
                    childFolders = emptyList(),
                    lists = listOf(disabled, first, second),
                ),
            )

        val executor = mockk<ExecuteCollectionList>()
        coEvery {
            executor.execute(match<ExecuteCollectionListRequest> { it.listId == "first" })
        } returns ExecuteCollectionListResult.Page(
            list = first,
            page = LogicalCatalogPage(
                items = listOf(item("work-1")),
                nextCursor = ResidualPageCursor(rawOffset = 20),
            ),
        )

        val model = FolderCatalogScreenModel(
            getConfiguredHomeSections = interactor,
            executeCollectionList = executor,
            materializeCanonicalTitleFromCatalog = mockk(relaxed = true),
        )
        model.bind("collection-1", "folder-1")
        advanceUntilIdle()

        val ready = model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
        ready.selectedListId shouldBe "first"
        val content = ready.content.shouldBeInstanceOf<FolderCatalogContent.Content>()
        content.items.map(CatalogItem::providerId) shouldBe listOf("work-1")
        content.nextCursor shouldBe ResidualPageCursor(rawOffset = 20)
    }

    @Test
    fun `folder list selection switches the executable catalog without mixing pages`() = runTest(dispatcher) {
        val interactor = mockk<GetConfiguredHomeSections>()
        val first = list("first", enabled = true, sortOrder = 0)
        val second = list("second", enabled = true, sortOrder = 1)
        every { interactor.subscribeFolder("collection-1", "folder-1", 4) } returns
            MutableStateFlow(
                HomeFolderBrowse(
                    collectionId = "collection-1",
                    folderId = "folder-1",
                    title = "Romance",
                    childFolders = emptyList(),
                    lists = listOf(first, second),
                ),
            )

        val executor = mockk<ExecuteCollectionList>()
        coEvery {
            executor.execute(match<ExecuteCollectionListRequest> { it.listId == "first" })
        } returns ExecuteCollectionListResult.Page(
            list = first,
            page = LogicalCatalogPage(listOf(item("first-work")), null),
        )
        coEvery {
            executor.execute(match<ExecuteCollectionListRequest> { it.listId == "second" })
        } returns ExecuteCollectionListResult.Page(
            list = second,
            page = LogicalCatalogPage(listOf(item("second-work")), null),
        )

        val model = FolderCatalogScreenModel(
            getConfiguredHomeSections = interactor,
            executeCollectionList = executor,
            materializeCanonicalTitleFromCatalog = mockk(relaxed = true),
        )
        model.bind("collection-1", "folder-1")
        advanceUntilIdle()

        model.selectList("second")
        advanceUntilIdle()

        val ready = model.state.value.shouldBeInstanceOf<FolderCatalogScreenState.Ready>()
        ready.selectedListId shouldBe "second"
        ready.content.shouldBeInstanceOf<FolderCatalogContent.Content>()
            .items
            .map(CatalogItem::providerId) shouldBe listOf("second-work")
    }

    @Test
    fun `folder catalog item materializes canonical title before navigation`() = runTest(dispatcher) {
        val interactor = mockk<GetConfiguredHomeSections>()
        every { interactor.subscribeFolder("collection-1", "folder-1", 4) } returns
            MutableStateFlow(null)

        val catalogItem = item("work-1")
        val materializer = mockk<MaterializeCanonicalTitleFromCatalog>()
        coEvery { materializer.execute(catalogItem) } returns CanonicalTitle(
            id = "canonical-1",
            displayTitle = "Work",
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1,
            updatedAt = 1,
        )

        val model = FolderCatalogScreenModel(
            getConfiguredHomeSections = interactor,
            executeCollectionList = mockk(relaxed = true),
            materializeCanonicalTitleFromCatalog = materializer,
        )
        model.openCatalogItem(catalogItem)
        advanceUntilIdle()

        model.events.first() shouldBe FolderCatalogEvent.OpenCanonicalTitle("canonical-1")
    }

    private fun item(id: String) = CatalogItem(
        provider = "kitsu",
        providerId = id,
        title = "Work",
        coverUrl = "https://example/$id.jpg",
    )

    private fun list(
        id: String,
        enabled: Boolean,
        sortOrder: Long,
    ) = CollectionList(
        id = id,
        collectionId = "collection-1",
        folderId = "folder-1",
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
}
