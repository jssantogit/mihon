package tachiyomi.domain.tsuzuki.collections.execution

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.cache.CacheFreshness
import tachiyomi.domain.tsuzuki.collections.cache.CatalogCacheKey
import tachiyomi.domain.tsuzuki.collections.cache.CatalogCacheLookup
import tachiyomi.domain.tsuzuki.collections.cache.InFlightQueryDeduplicator
import tachiyomi.domain.tsuzuki.collections.cache.MemoryCatalogCache
import tachiyomi.domain.tsuzuki.collections.cache.PersistentCatalogCacheStore
import tachiyomi.domain.tsuzuki.collections.cache.classifyCacheWindow
import tachiyomi.domain.tsuzuki.collections.cache.safeCacheDeadline
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.model.CollectionFolder
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionOrigin
import tachiyomi.domain.tsuzuki.collections.model.TsuzukiCollection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.collections.scheduler.CollectionQueryScheduler

class ExecuteCollectionListTest {

    @Test
    fun `missing disabled and deleted definitions fail before provider execution`() = runTest {
        val fixture = fixture()

        fixture.executor.execute(ExecuteCollectionListRequest("missing"))
            .shouldBeInstanceOf<ExecuteCollectionListResult.DefinitionUnavailable>()
        fixture.provider.calls shouldBe 0

        fixture.store.putGraph(list(enabled = false))
        fixture.executor.execute(ExecuteCollectionListRequest("list"))
            .shouldBeInstanceOf<ExecuteCollectionListResult.DefinitionUnavailable>()
        fixture.provider.calls shouldBe 0

        fixture.store.putGraph(list(deletedAt = 10))
        fixture.executor.execute(ExecuteCollectionListRequest("list"))
            .shouldBeInstanceOf<ExecuteCollectionListResult.DefinitionUnavailable>()
        fixture.provider.calls shouldBe 0
    }

    @Test
    fun `unknown provider fails explicitly`() = runTest {
        val fixture = fixture()
        fixture.store.putGraph(list(providerId = "missing-provider"))

        fixture.executor.execute(ExecuteCollectionListRequest("list")) shouldBe
            ExecuteCollectionListResult.ProviderUnavailable("missing-provider")
        fixture.provider.calls shouldBe 0
    }

    @Test
    fun `unsupported global sort fails before provider fetch`() = runTest {
        val fixture = fixture()
        fixture.store.putGraph(list(sort = CatalogSort.RATING_DESC))

        fixture.executor.execute(ExecuteCollectionListRequest("list")) shouldBe
            ExecuteCollectionListResult.UnsupportedGlobalSort(CatalogSort.RATING_DESC)
        fixture.provider.calls shouldBe 0
    }

    @Test
    fun `unsupported residual fails before provider fetch`() = runTest {
        val fixture = fixture()
        fixture.store.putGraph(
            list(
                query = QueryExpression.Predicate(
                    QueryField.AUTHOR,
                    QueryOperator.EQUALS,
                    QueryValue.of("Author"),
                ),
            ),
        )

        fixture.executor.execute(ExecuteCollectionListRequest("list"))
            .shouldBeInstanceOf<ExecuteCollectionListResult.UnsupportedResidual>()
        fixture.provider.calls shouldBe 0
    }

    @Test
    fun `integrated execution refills logical page across raw provider pages`() = runTest {
        val fixture = fixture(
            dataset = listOf(
                item("1", chapterCount = 1),
                item("2", chapterCount = 101),
                item("3", chapterCount = 2),
                item("4", chapterCount = 102),
            ),
            maxProviderPageSize = 2,
        )
        fixture.store.putGraph(
            list(
                query = QueryExpression.Predicate(
                    QueryField.CHAPTER_COUNT,
                    QueryOperator.GREATER_THAN,
                    QueryValue.of(100),
                ),
            ),
        )

        val result = fixture.executor.execute(
            ExecuteCollectionListRequest(
                listId = "list",
                pageSize = 2,
                cachePolicy = CollectionExecutionCachePolicy(
                    mode = CollectionCacheMode.NETWORK_ONLY,
                ),
            ),
        ).shouldBeInstanceOf<ExecuteCollectionListResult.Page>()

        result.page.items.map { it.providerId } shouldContainExactly listOf("2", "4")
        result.page.nextCursor shouldBe null
        fixture.provider.calls shouldBe 3
    }

    @Test
    fun `fresh memory cache avoids provider fetch`() = runTest {
        val fixture = fixture(now = 1_050)
        fixture.store.putGraph(list())

        val key = CatalogCacheKey.fromExpression(
            providerId = "fake",
            expression = null,
            sort = CatalogSort.POPULARITY_DESC,
            rawOffset = 0,
            pageSize = 1,
        )
        fixture.resources.memoryCache.put(
            key = key,
            page = page(item("cached")),
            fetchedAt = 1_000,
            ttlMillis = 100,
            staleWhileRevalidateMillis = 100,
        )

        val result = fixture.executor.execute(
            ExecuteCollectionListRequest(
                listId = "list",
                pageSize = 1,
            ),
        ).shouldBeInstanceOf<ExecuteCollectionListResult.Page>()

        result.page.items.map { it.providerId } shouldContainExactly listOf("cached")
        fixture.provider.calls shouldBe 0
    }

    @Test
    fun `persistent cache is usable when memory cache misses`() = runTest {
        val fixture = fixture(now = 1_050)
        fixture.store.putGraph(list())

        val key = CatalogCacheKey.fromExpression(
            providerId = "fake",
            expression = null,
            sort = CatalogSort.POPULARITY_DESC,
            rawOffset = 0,
            pageSize = 1,
        )
        fixture.persistentCache.put(
            key = key,
            page = page(item("persisted")),
            fetchedAt = 1_000,
            ttlMillis = 100,
            staleWhileRevalidateMillis = 100,
        )

        val result = fixture.executor.execute(
            ExecuteCollectionListRequest(
                listId = "list",
                pageSize = 1,
                cachePolicy = CollectionExecutionCachePolicy(mode = CollectionCacheMode.CACHE_ONLY),
            ),
        ).shouldBeInstanceOf<ExecuteCollectionListResult.Page>()

        result.page.items.map { it.providerId } shouldContainExactly listOf("persisted")
        fixture.provider.calls shouldBe 0
    }

    @Test
    fun `cache only miss is explicit and does not touch provider`() = runTest {
        val fixture = fixture()
        fixture.store.putGraph(list())

        fixture.executor.execute(
            ExecuteCollectionListRequest(
                listId = "list",
                pageSize = 1,
                cachePolicy = CollectionExecutionCachePolicy(mode = CollectionCacheMode.CACHE_ONLY),
            ),
        ) shouldBe ExecuteCollectionListResult.CacheMiss

        fixture.provider.calls shouldBe 0
    }

    @Test
    fun `stale cache serves immediately and schedules one background refresh`() = runTest {
        val fixture = fixture(
            dataset = listOf(item("fresh")),
            now = 10,
            maxConcurrentPerProvider = 1,
        )
        fixture.store.putGraph(list())

        val key = CatalogCacheKey.fromExpression(
            providerId = "fake",
            expression = null,
            sort = CatalogSort.POPULARITY_DESC,
            rawOffset = 0,
            pageSize = 1,
        )
        fixture.resources.memoryCache.put(
            key = key,
            page = page(item("stale")),
            fetchedAt = 0,
            ttlMillis = 0,
            staleWhileRevalidateMillis = 100,
        )

        val result = fixture.executor.execute(
            ExecuteCollectionListRequest(
                listId = "list",
                pageSize = 1,
                cachePolicy = CollectionExecutionCachePolicy(
                    ttlMillis = 100,
                    staleWhileRevalidateMillis = 100,
                ),
            ),
        ).shouldBeInstanceOf<ExecuteCollectionListResult.Page>()

        result.page.items.map { it.providerId } shouldContainExactly listOf("stale")

        runCurrent()

        fixture.provider.calls shouldBe 1
        fixture.resources.refreshCoordinator.activeCount() shouldBe 0

        val refreshed = fixture.resources.memoryCache.get(key, now = 10)
            .shouldBeInstanceOf<CatalogCacheLookup.Hit>()
        refreshed.freshness shouldBe CacheFreshness.FRESH
        refreshed.page.items.map { it.providerId } shouldContainExactly listOf("fresh")
    }

    @Test
    fun `simultaneous identical executions deduplicate raw provider request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fixture = fixture(
            dataset = listOf(item("shared")),
            providerGate = gate,
            maxConcurrentPerProvider = 2,
        )
        fixture.store.putGraph(list())

        val request = ExecuteCollectionListRequest(
            listId = "list",
            pageSize = 1,
            cachePolicy = CollectionExecutionCachePolicy(mode = CollectionCacheMode.NETWORK_ONLY),
        )

        val first = async { fixture.executor.execute(request) }
        val second = async { fixture.executor.execute(request) }

        runCurrent()
        fixture.provider.calls shouldBe 1

        gate.complete(Unit)
        runCurrent()

        first.await().shouldBeInstanceOf<ExecuteCollectionListResult.Page>()
        second.await().shouldBeInstanceOf<ExecuteCollectionListResult.Page>()
        fixture.provider.calls shouldBe 1
    }

    private fun TestScope.fixture(
        dataset: List<CatalogItem> = listOf(item("network")),
        now: Long = 1_000,
        maxProviderPageSize: Int = 20,
        providerGate: CompletableDeferred<Unit>? = null,
        maxConcurrentPerProvider: Int = 2,
    ): Fixture {
        val store = FakeCollectionStore()
        val provider = FakeProvider(
            dataset = dataset,
            maxProviderPageSize = maxProviderPageSize,
            gate = providerGate,
        )
        val persistentCache = FakePersistentCache()
        val resources = TestResources(
            scope = backgroundScope,
            maxConcurrentPerProvider = maxConcurrentPerProvider,
        )
        val executor = ExecuteCollectionList(
            store = store,
            providerRegistry = FakeRegistry(provider),
            persistentCache = persistentCache,
            resources = resources,
            clock = { now },
        )
        return Fixture(
            store = store,
            provider = provider,
            persistentCache = persistentCache,
            resources = resources,
            executor = executor,
        )
    }

    private fun list(
        providerId: String = "fake",
        query: QueryExpression? = null,
        sort: CatalogSort = CatalogSort.POPULARITY_DESC,
        enabled: Boolean = true,
        deletedAt: Long? = null,
    ): CollectionList = CollectionList(
        id = "list",
        collectionId = "collection",
        folderId = "folder",
        title = "List",
        providerId = providerId,
        query = query,
        sort = sort,
        sortOrder = 0,
        enabled = enabled,
        origin = CollectionOrigin.USER,
        createdAt = 1,
        updatedAt = 1,
        deletedAt = deletedAt,
    )

    private fun item(
        id: String,
        chapterCount: Int? = null,
    ): CatalogItem = CatalogItem(
        provider = "fake",
        providerId = id,
        title = "Title $id",
        chapterCount = chapterCount,
    )

    private fun page(item: CatalogItem): CatalogPage = CatalogPage(
        items = listOf(item),
        hasNextPage = false,
        totalCount = 1,
    )

    private data class Fixture(
        val store: FakeCollectionStore,
        val provider: FakeProvider,
        val persistentCache: FakePersistentCache,
        val resources: TestResources,
        val executor: ExecuteCollectionList,
    )

    private class FakeRegistry(
        private val provider: CollectionQueryProvider,
    ) : CollectionQueryProviderRegistry {
        override fun get(providerId: String): CollectionQueryProvider? {
            return provider.takeIf { it.providerId == providerId }
        }
    }

    private class FakeProvider(
        private val dataset: List<CatalogItem>,
        maxProviderPageSize: Int,
        private val gate: CompletableDeferred<Unit>?,
    ) : CollectionQueryProvider {
        var calls = 0

        override val providerId: String = "fake"

        override val capabilities: ProviderQueryCapabilities = object : ProviderQueryCapabilities {
            override val providerId: String = "fake"
            override val supportsOffsetPaging: Boolean = true
            override val maxPageSize: Int = maxProviderPageSize

            override fun canPushPredicate(
                field: QueryField,
                operator: QueryOperator,
                value: QueryValue,
            ): Boolean = false

            override fun canPushSort(sort: CatalogSort): Boolean {
                return sort == CatalogSort.POPULARITY_DESC
            }
        }

        override suspend fun fetch(
            pushdownExpression: QueryExpression?,
            sort: CatalogSort,
            offset: Int,
            limit: Int,
        ): Result<CatalogPage> {
            calls++
            gate?.await()
            val end = minOf(offset + limit, dataset.size)
            val items = if (offset >= dataset.size) emptyList() else dataset.subList(offset, end)
            return Result.success(
                CatalogPage(
                    items = items,
                    hasNextPage = end < dataset.size,
                    totalCount = dataset.size,
                ),
            )
        }
    }

    private class TestResources(
        scope: kotlinx.coroutines.CoroutineScope,
        maxConcurrentPerProvider: Int,
    ) : CollectionExecutionResources {
        override val memoryCache = MemoryCatalogCache()
        override val inFlightDeduplicator = InFlightQueryDeduplicator(scope)
        override val scheduler = CollectionQueryScheduler(
            scope = scope,
            maxConcurrent = 4,
            maxConcurrentPerProvider = maxConcurrentPerProvider,
        )
        override val refreshCoordinator = CatalogRefreshCoordinator(scheduler)
    }

    private class FakePersistentCache : PersistentCatalogCacheStore {
        private data class Entry(
            val page: CatalogPage,
            val fetchedAt: Long,
            val expiresAt: Long,
            val staleUntil: Long,
        )

        private val entries = mutableMapOf<CatalogCacheKey, Entry>()

        override suspend fun get(
            key: CatalogCacheKey,
            now: Long,
        ): CatalogCacheLookup {
            val entry = entries[key] ?: return CatalogCacheLookup.Miss
            val freshness = classifyCacheWindow(
                now = now,
                expiresAt = entry.expiresAt,
                staleUntil = entry.staleUntil,
            ) ?: return CatalogCacheLookup.Miss

            return CatalogCacheLookup.Hit(
                page = entry.page,
                freshness = freshness,
                fetchedAt = entry.fetchedAt,
            )
        }

        override suspend fun put(
            key: CatalogCacheKey,
            page: CatalogPage,
            fetchedAt: Long,
            ttlMillis: Long,
            staleWhileRevalidateMillis: Long,
        ) {
            val expiresAt = safeCacheDeadline(fetchedAt, ttlMillis)
            entries[key] = Entry(
                page = page,
                fetchedAt = fetchedAt,
                expiresAt = expiresAt,
                staleUntil = safeCacheDeadline(expiresAt, staleWhileRevalidateMillis),
            )
        }

        override suspend fun remove(key: CatalogCacheKey) {
            entries.remove(key)
        }

        override suspend fun prune(now: Long) {
            entries.entries.removeAll { (_, entry) -> entry.staleUntil < now }
        }
    }

    private class FakeCollectionStore : CollectionStore {
        private val collections = mutableMapOf<String, TsuzukiCollection>()
        private val folders = mutableMapOf<String, CollectionFolder>()
        private val lists = mutableMapOf<String, CollectionList>()

        suspend fun putGraph(list: CollectionList) {
            collections[list.collectionId] = TsuzukiCollection(
                id = list.collectionId,
                title = "Collection",
                origin = CollectionOrigin.USER,
                sortOrder = 0,
                createdAt = 1,
                updatedAt = 1,
            )
            folders[list.folderId] = CollectionFolder(
                id = list.folderId,
                collectionId = list.collectionId,
                title = "Folder",
                origin = CollectionOrigin.USER,
                sortOrder = 0,
                createdAt = 1,
                updatedAt = 1,
            )
            lists[list.id] = list
        }

        override suspend fun getCollection(id: String): TsuzukiCollection? = collections[id]
        override suspend fun getCollections(includeDeleted: Boolean): List<TsuzukiCollection> = collections.values.toList()
        override fun observeCollections(): Flow<List<TsuzukiCollection>> = flowOf(collections.values.toList())
        override suspend fun upsertCollection(collection: TsuzukiCollection) {
            collections[collection.id] = collection
        }

        override suspend fun getFolder(id: String): CollectionFolder? = folders[id]
        override suspend fun getFolders(collectionId: String, includeDeleted: Boolean): List<CollectionFolder> {
            return folders.values.filter { it.collectionId == collectionId }
        }
        override fun observeFolders(collectionId: String): Flow<List<CollectionFolder>> {
            return flowOf(folders.values.filter { it.collectionId == collectionId })
        }
        override suspend fun upsertFolder(folder: CollectionFolder) {
            folders[folder.id] = folder
        }

        override suspend fun getList(id: String): CollectionList? = lists[id]
        override suspend fun getLists(folderId: String, includeDeleted: Boolean): List<CollectionList> {
            return lists.values.filter { it.folderId == folderId }
        }
        override fun observeLists(folderId: String): Flow<List<CollectionList>> {
            return flowOf(lists.values.filter { it.folderId == folderId })
        }
        override suspend fun upsertList(list: CollectionList) {
            lists[list.id] = list
        }
    }
}
