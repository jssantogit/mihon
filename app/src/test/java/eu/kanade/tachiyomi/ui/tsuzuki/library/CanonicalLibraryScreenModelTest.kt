package eu.kanade.tachiyomi.ui.tsuzuki.library

import eu.kanade.domain.tsuzuki.library.interactor.RemoveUnifiedLibraryTitle
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
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
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.library.interactor.ObserveCanonicalLibrary
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
import tachiyomi.domain.tsuzuki.reader.model.CanonicalReadingStart
import tachiyomi.domain.tsuzuki.reader.service.CanonicalReadingStartResolver
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
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = fakeMigration,
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
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
    fun `search filters primary library by canonical display title`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
        )
        fakeRepo.emitItems(
            listOf(
                canonicalItem("title-1", "Frieren"),
                canonicalItem("title-2", "Dungeon Meshi"),
            ),
        )
        advanceUntilIdle()

        screenModel.search("frie")
        advanceUntilIdle()

        val filtered = screenModel.state.value
        filtered.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        filtered.searchQuery shouldBe "frie"
        filtered.items.map { it.title.displayTitle } shouldBe listOf("Frieren")

        screenModel.search(null)
        advanceUntilIdle()

        val restored = screenModel.state.value
        restored.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        restored.items.map { it.title.displayTitle } shouldBe listOf("Frieren", "Dungeon Meshi")
    }

    @Test
    fun `category filter uses canonical title categories and supports uncategorized`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
        )
        fakeRepo.emitItems(
            listOf(
                canonicalItem(
                    id = "title-1",
                    title = "Frieren",
                    categories = listOf(category(1L, "Reading")),
                ),
                canonicalItem(
                    id = "title-2",
                    title = "Dungeon Meshi",
                    categories = emptyList(),
                ),
            ),
        )
        advanceUntilIdle()

        screenModel.selectCategory(1L)
        advanceUntilIdle()

        val categorized = screenModel.state.value
        categorized.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        categorized.selectedCategoryId shouldBe 1L
        categorized.items.map { it.title.id } shouldBe listOf("title-1")

        screenModel.selectCategory(Category.UNCATEGORIZED_ID)
        advanceUntilIdle()

        val uncategorized = screenModel.state.value
        uncategorized.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        uncategorized.items.map { it.title.id } shouldBe listOf("title-2")

        screenModel.selectCategory(null)
        advanceUntilIdle()

        val all = screenModel.state.value
        all.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        all.items.map { it.title.id } shouldBe listOf("title-1", "title-2")
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
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
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
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
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
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = fakeMigration,
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
        )
        advanceUntilIdle()

        fakeMigration.callCount shouldBe 1
    }

    @Test
    fun `library stays loading and unobserved until initial migration finishes`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val migrationGate = CompletableDeferred<Unit>()
        val fakeMigration = FakeMigrateMihonLibraryToCanonical(
            beforeReturn = { migrationGate.await() },
        )
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = fakeMigration,
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
        )

        val item = CanonicalLibraryItem(
            title = CanonicalTitle(
                id = "title-after-migration",
                displayTitle = "Dandadan",
                identityState = CanonicalIdentityState.RESOLVED,
                createdAt = 700L,
                updatedAt = 700L,
            ),
            entry = CanonicalLibraryEntry(
                canonicalTitleId = "title-after-migration",
                status = LibraryStatus.READING,
                favorite = true,
                addedAt = 700L,
                updatedAt = 700L,
            ),
        )
        fakeRepo.emitItems(listOf(item))
        advanceUntilIdle()

        screenModel.state.value shouldBe CanonicalLibraryScreenState.Loading
        fakeRepo.observeCallCount shouldBe 0

        migrationGate.complete(Unit)
        advanceUntilIdle()

        fakeRepo.observeCallCount shouldBe 1
        val state = screenModel.state.value
        state.shouldBeInstanceOf<CanonicalLibraryScreenState.Success>()
        state.items shouldBe listOf(item)
    }

    @Test
    fun `migration failure does not block observation and UI state`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val failingMigration = FakeMigrateMihonLibraryToCanonical(shouldFail = true)
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = failingMigration,
            resolveCanonicalReadingStart = FakeCanonicalReadingStartResolver(),
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

    @Test
    fun `read or continue emits canonical reader event`() = runTest(testDispatcher) {
        val fakeRepo = FakeCanonicalLibraryRepository()
        val resolver = FakeCanonicalReadingStartResolver(
            result = CanonicalReadingStart.Ready("chapter-42"),
        )
        val screenModel = CanonicalLibraryScreenModel(
            observeCanonicalLibrary = ObserveCanonicalLibrary(fakeRepo),
            setCanonicalLibraryStatus = SetCanonicalLibraryStatus(fakeRepo),
            removeUnifiedLibraryTitle = RemoveUnifiedLibraryTitle(
                getFavoriteMihonMangaIds = { emptyList() },
                setMihonFavorite = { _, _ -> true },
                removeCanonical = fakeRepo::remove,
            ),
            migrateMihonLibraryToCanonical = FakeMigrateMihonLibraryToCanonical(),
            resolveCanonicalReadingStart = resolver,
        )
        advanceUntilIdle()

        val event = async { screenModel.events.first() }
        screenModel.readOrContinue("title-42", "Title 42")
        advanceUntilIdle()

        event.await() shouldBe CanonicalLibraryEvent.OpenReader("chapter-42")
        resolver.lastCanonicalTitleId shouldBe "title-42"
    }

    private fun category(id: Long, name: String) = Category(
        id = id,
        name = name,
        order = id,
        flags = 0L,
    )

    private fun canonicalItem(
        id: String,
        title: String,
        categories: List<Category> = emptyList(),
    ) = CanonicalLibraryItem(
        title = CanonicalTitle(
            id = id,
            displayTitle = title,
            identityState = CanonicalIdentityState.RESOLVED,
            createdAt = 1000L,
            updatedAt = 1000L,
        ),
        entry = CanonicalLibraryEntry(
            canonicalTitleId = id,
            status = LibraryStatus.READING,
            favorite = true,
            addedAt = 1000L,
            updatedAt = 1000L,
        ),
        categories = categories,
    )

    private class FakeMigrateMihonLibraryToCanonical(
        private val shouldFail: Boolean = false,
        private val beforeReturn: suspend () -> Unit = {},
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
            beforeReturn()
            return CanonicalMigrationReport(0, 0, 0)
        }
    }

    private class FakeCanonicalLibraryRepository : CanonicalLibraryRepository {
        val entries = mutableMapOf<String, CanonicalLibraryEntry>()
        val removedIds = mutableListOf<String>()
        var observeCallCount = 0
        private val itemsFlow = MutableStateFlow<List<CanonicalLibraryItem>>(emptyList())

        fun emitItems(items: List<CanonicalLibraryItem>) {
            itemsFlow.value = items
        }

        override suspend fun get(canonicalTitleId: String): CanonicalLibraryEntry? = entries[canonicalTitleId]

        override fun getAllAsFlow(): Flow<List<CanonicalLibraryEntry>> = MutableStateFlow(entries.values.toList())

        override fun getAllItemsAsFlow(): Flow<List<CanonicalLibraryItem>> {
            observeCallCount++
            return itemsFlow
        }

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
        override suspend fun getOrCreateByExternalIdentity(
            title: CanonicalTitle,
            identity: ExternalIdentity,
        ): CanonicalTitle = title
        override suspend fun insert(title: CanonicalTitle) {}
        override suspend fun addExternalIdentity(identity: ExternalIdentity) {}
    }

    private class FakeCanonicalReadingStartResolver(
        private val result: CanonicalReadingStart = CanonicalReadingStart.Unavailable("unused"),
    ) : CanonicalReadingStartResolver {
        var lastCanonicalTitleId: String? = null

        override suspend fun execute(canonicalTitleId: String): CanonicalReadingStart {
            lastCanonicalTitleId = canonicalTitleId
            return when (result) {
                is CanonicalReadingStart.Ready -> result
                is CanonicalReadingStart.Unavailable -> result.copy(canonicalTitleId = canonicalTitleId)
            }
        }
    }

    private class FakeSourceTitleMappingRepository : SourceTitleMappingRepository {
        override suspend fun getByCanonicalTitleId(canonicalTitleId: String): List<SourceTitleMapping> = emptyList()
        override fun getByCanonicalTitleIdAsFlow(
            canonicalTitleId: String,
        ): Flow<List<SourceTitleMapping>> = MutableStateFlow(emptyList())
        override suspend fun getBySource(sourceId: Long, sourceUrl: String): SourceTitleMapping? = null
        override suspend fun upsert(mapping: SourceTitleMapping) {}
        override suspend fun setPreferredForTitle(canonicalTitleId: String, mappingId: String?, updatedAt: Long) {}
    }
}
