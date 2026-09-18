package eu.kanade.tachiyomi.ui.tsuzuki.library

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.interactor.RemoveCanonicalLibraryItem
import tachiyomi.domain.tsuzuki.library.interactor.SetCanonicalLibraryStatus
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository

@OptIn(ExperimentalCoroutinesApi::class)
class CanonicalLibraryScreenModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `source-less entries are observable`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeCanonicalLibraryItem = RemoveCanonicalLibraryItem(fakeRepo),
        )

        val sourcelessItem = CanonicalLibraryItem(
            title = CanonicalTitle(
                id = "title-1",
                displayTitle = "Frieren",
                identityState = CanonicalIdentityState.RESOLVED,
                createdAt = 1000L,
                updatedAt = 1000L,
            ),
            entry = CanonicalLibraryEntry(
                canonicalTitleId = "title-1",
                status = LibraryStatus.READING,
                favorite = true,
                addedAt = 1000L,
                updatedAt = 1000L,
            ),
            primaryMapping = null,
        )

        fakeRepo.emitItems(listOf(sourcelessItem))
        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        state.items shouldBe listOf(sourcelessItem)
        state.items.first().primaryMapping shouldBe null
    }

    @Test
    fun `status changes update the entry and list`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = "title-2",
            status = LibraryStatus.PLANNING,
            favorite = true,
            addedAt = 1000L,
            updatedAt = 1000L,
        )
        fakeRepo.entries["title-2"] = entry

        val item = CanonicalLibraryItem(
            title = CanonicalTitle(
                id = "title-2",
                displayTitle = "Dungeon Meshi",
                identityState = CanonicalIdentityState.PARTIALLY_RESOLVED,
                createdAt = 1000L,
                updatedAt = 1000L,
            ),
            entry = entry,
        )
        fakeRepo.emitItems(listOf(item))

        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeCanonicalLibraryItem = RemoveCanonicalLibraryItem(fakeRepo),
        )
        advanceUntilIdle()

        screenModel.setStatus("title-2", LibraryStatus.COMPLETED)
        advanceUntilIdle()

        val updatedEntry = fakeRepo.entries["title-2"]
        updatedEntry?.status shouldBe LibraryStatus.COMPLETED
        updatedEntry?.addedAt shouldBe 1000L
    }

    @Test
    fun `remove deletes membership only`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = "title-3",
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 1000L,
            updatedAt = 1000L,
        )
        fakeRepo.entries["title-3"] = entry

        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeCanonicalLibraryItem = RemoveCanonicalLibraryItem(fakeRepo),
        )
        advanceUntilIdle()

        screenModel.removeItem("title-3")
        advanceUntilIdle()

        fakeRepo.entries.containsKey("title-3") shouldBe false
        fakeRepo.removedIds shouldBe listOf("title-3")
    }

    private class FakeCanonicalLibraryRepository : CanonicalLibraryRepository {
        val entries = mutableMapOf<String, CanonicalLibraryEntry>()
        val removedIds = mutableListOf<String>()
        private val itemsFlow = MutableStateFlow<List<CanonicalLibraryItem>>(emptyList())

        fun emitItems(items: List<CanonicalLibraryItem>) {
            itemsFlow.value = items
        }

        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? = entries[canonicalTitleId]

        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(entries.values.toList())

        override fun getAllItemsAsFlow(): Flow<List<CanonicalLibraryItem>> = itemsFlow

        override suspend fun upsert(entry: CanonicalLibraryEntry) {
            entries[entry.canonicalTitleId] = entry
        }

        override suspend fun remove(canonicalTitleId: String) {
            entries.remove(canonicalTitleId)
            removedIds += canonicalTitleId
        }
    }
}
