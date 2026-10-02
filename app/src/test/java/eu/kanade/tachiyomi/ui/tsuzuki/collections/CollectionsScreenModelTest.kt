package eu.kanade.tachiyomi.ui.tsuzuki.collections

import io.mockk.coEvery
import io.mockk.coVerifyOrder
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
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
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
    fun `moving a collection across multiple positions persists the displayed order`() = runTest(dispatcher) {
        val first = collection(id = "first", sortOrder = 10)
        val second = collection(id = "second", sortOrder = 20)
        val third = collection(id = "third", sortOrder = 30)
        val source = listOf(first, second, third)

        val store = mockk<CollectionStore>()
        every { store.observeCollections() } returns flowOf(source)
        every { store.observeFolders(any()) } returns flowOf(emptyList())

        val manager = mockk<ManageCollectionDefinitions>()
        coEvery { manager.reorderCollection(any(), any()) } answers {
            val id = firstArg<String>()
            val sortOrder = secondArg<Long>()
            source.first { it.id == id }.copy(sortOrder = sortOrder)
        }

        val model = CollectionsScreenModel(
            store = store,
            manager = manager,
            exportCollections = mockk(),
            importCollections = mockk(),
            executeCollectionList = mockk<ExecuteCollectionList>(),
        )
        advanceUntilIdle()

        model.dispatch(CollectionsAction.MoveCollection(first, delta = 2))
        advanceUntilIdle()

        coVerifyOrder {
            manager.reorderCollection("second", 10)
            manager.reorderCollection("third", 20)
            manager.reorderCollection("first", 30)
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
