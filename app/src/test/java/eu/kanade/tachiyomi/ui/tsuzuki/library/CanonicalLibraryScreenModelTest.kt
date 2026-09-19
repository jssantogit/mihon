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
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
import tachiyomi.domain.tsuzuki.library.interactor.RemoveCanonicalLibraryItem
import tachiyomi.domain.tsuzuki.library.interactor.SetCanonicalLibraryStatus
import tachiyomi.domain.tsuzuki.library.model.CanonicalLibraryItem
import tachiyomi.domain.tsuzuki.migration.interactor.MigrateMihonLibraryToCanonical
import tachiyomi.domain.tsuzuki.migration.model.CanonicalMigrationReport
import tachiyomi.domain.tsuzuki.migration.model.MihonLibrarySnapshot
import tachiyomi.domain.tsuzuki.migration.service.MihonLibraryGateway
import tachiyomi.domain.tsuzuki.model.CanonicalIdentityState
import tachiyomi.domain.tsuzuki.model.CanonicalLibraryEntry
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.model.ExternalIdentity
import tachiyomi.domain.tsuzuki.model.LibraryStatus
import tachiyomi.domain.tsuzuki.model.SourceTitleMapping
import tachiyomi.domain.tsuzuki.repository.CanonicalLibraryRepository
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository
import tachiyomi.domain.tsuzuki.repository.SourceTitleMappingRepository

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
    fun `canonical entries are observable without any source state`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val fakeMigration = FakeMigrateMihonLibraryToCanonical()
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeCanonicalLibraryItem = RemoveCanonicalLibraryItem(fakeRepo),
            migrateMihonLibraryToCanonical = fakeMigration,
        )

        val canonicalItem = CanonicalLibraryItem(
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
        )

        fakeRepo.emitItems(listOf(canonicalItem))
        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        state.items shouldBe listOf(canonicalItem)
        state.items.first().title.id shouldBe "title-1"
        state.items.first().entry.canonicalTitleId shouldBe "title-1"
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
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
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
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
        )
        advanceUntilIdle()

        screenModel.removeItem("title-3")
        advanceUntilIdle()

        fakeRepo.entries.containsKey("title-3") shouldBe false
        fakeRepo.removedIds shouldBe listOf("title-3")
    }

    @Test
    fun `migration runs on init`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val fakeMigration = FakeMigrateMihonLibraryToCanonical()
        CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeCanonicalLibraryItem = RemoveCanonicalLibraryItem(fakeRepo),
            migrateMihonLibraryToCanonical = fakeMigration,
        )
        advanceUntilIdle()

        fakeMigration.callCount shouldBe 1
    }

    @Test
    fun `migration failure does not block observation and UI state`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val failingMigration = FakeMigrateMihonLibraryToCanonical(shouldFail = true)
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeCanonicalLibraryItem = RemoveCanonicalLibraryItem(fakeRepo),
            migrateMihonLibraryToCanonical = failingMigration,
        )

        val item = CanonicalLibraryItem(
            title = CanonicalTitle(
                id = "title-fail",
                displayTitle = "Solo Leveling",
                identityState = CanonicalIdentityState.RESOLVED,
                createdAt = 500L,
                updatedAt = 500L,
            ),
            entry = CanonicalLibraryEntry(
                canonicalTitleId = "title-fail",
                status = LibraryStatus.PLANNING,
                favorite = true,
                addedAt = 500L,
                updatedAt = 500L,
            ),
        )
        fakeRepo.emitItems(listOf(item))
        advanceUntilIdle()

        val state = screenModel.state.value
        state.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        state.items shouldBe listOf(item)
    }

    private class FakeMigrateMihonLibraryToCanonical(
        private val shouldFail: Boolean = false,
    ) : MigrateMihonLibraryToCanonical(
        gateway = object : MihonLibraryGateway {
            override suspend fun snapshot(): List<MihonLibrarySnapshot> = emptyList()
        },
        sourceTitleMappingRepository = FakeSourceTitleMappingRepository(),
        materializeCanonicalTitle = MaterializeCanonicalTitle(FakeCanonicalTitleRepository()),
        canonicalLibraryRepository = FakeCanonicalLibraryRepository(),
    ) {
        var callCount = 0

        override suspend fun execute(): CanonicalMigrationReport {
            callCount++
            if (shouldFail) {
                throw RuntimeException("Migration failed")
            }
            return CanonicalMigrationReport(0, 0, 0)
        }
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

    private class FakeCanonicalTitleRepository : CanonicalTitleRepository {
        override suspend fun getById(id: String): CanonicalTitle? = null
        override fun getByIdAsFlow(id: String): Flow<CanonicalTitle?> = MutableStateFlow(null)
        override suspend fun getByExternalIdentity(provider: String, externalId: String): CanonicalTitle? = null
        override suspend fun getOrCreateByExternalIdentity(title: CanonicalTitle, identity: ExternalIdentity): CanonicalTitle = title
        override suspend fun insert(title: CanonicalTitle) {}
        override suspend fun addExternalIdentity(identity: ExternalIdentity) {}
    }

    private class FakeSourceTitleMappingRepository : SourceTitleMappingRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> = emptyList()
        override fun getByCanonicalTitleIdAsFlow(canonicalTitleId: String): Flow<List<SourceTitleMapping>> = MutableStateFlow(emptyList())
        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? = null
        override suspend fun upsert(mapping: SourceTitleMapping) {}
    }
}
