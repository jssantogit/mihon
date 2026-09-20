package tachiyomi.domain.tsuzuki.library.interactor

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository\nimport tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository\nimport tachiyomi.domain.tsuzuki.model.SourceTitleMapping

class CanonicalLibraryOperationsTest {

    @Test
    fun `ObserveCanonicalLibrary returns flow of joined items`() = runTest {
        val repository = FakeCanonicalLibraryRepository()
        val observeCanonicalLibrary = ObserveCanonicalLibrary(repository, FakeSourceTitleMappingRepository())

        val item1 = CanonicalLibraryItem(
            title = CanonicalTitle("t1", "Title 1", CanonicalIdentityState.RESOLVED, 100L, 100L),
            entry = CanonicalLibraryEntry("t1", LibraryStatus.PLANNING, true, 100L, 100L),
        )
        repository.itemsFlow.value = listOf(item1)

        val items = observeCanonicalLibrary.subscribe().first()
        items shouldBe listOf(item1)
    }

    @Test
    fun `SetCanonicalLibraryStatus updates status and updatedAt while preserving addedAt`() = runTest {
        val repository = FakeCanonicalLibraryRepository()
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = "t1",
            status = LibraryStatus.PLANNING,
            favorite = true,
            addedAt = 1000L,
            updatedAt = 1000L,
        )
        repository.entries["t1"] = entry

        val setStatus = SetCanonicalLibraryStatus(
            canonicalLibraryRepository = repository,
            clock = { 5000L },
        )

        setStatus.execute("t1", LibraryStatus.READING)

        val updated = repository.entries["t1"]
        updated shouldBe CanonicalLibraryEntry(
            canonicalTitleId = "t1",
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 1000L,
            updatedAt = 5000L,
        )
    }

    @Test
    fun `SetCanonicalLibraryStatus does nothing if entry does not exist`() = runTest {
        val repository = FakeCanonicalLibraryRepository()
        val setStatus = SetCanonicalLibraryStatus(
            canonicalLibraryRepository = repository,
            clock = { 5000L },
        )

        setStatus.execute("nonexistent", LibraryStatus.READING)
        repository.entries.isEmpty() shouldBe true
    }

    @Test
    fun `RemoveCanonicalLibraryItem removes membership only`() = runTest {
        val repository = FakeCanonicalLibraryRepository()
        val entry = CanonicalLibraryEntry(
            canonicalTitleId = "t1",
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 1000L,
            updatedAt = 1000L,
        )
        repository.entries["t1"] = entry

        val removeInteractor = RemoveCanonicalLibraryItem(repository)
        removeInteractor.execute("t1")

        repository.entries.containsKey("t1") shouldBe false
        repository.removedIds shouldBe listOf("t1")
    }

    private class FakeCanonicalLibraryRepository : CanonicalLibraryRepository {
        val entries = mutableMapOf<String, CanonicalLibraryEntry>()
        val removedIds = mutableListOf<String>()
        val itemsFlow = MutableStateFlow<List<CanonicalLibraryItem>>(emptyList())

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
