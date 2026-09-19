package tachiyomi.domain.tsuzuki.collections.execution

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.cache.CacheFreshness
import tachiyomi.domain.tsuzuki.collections.cache.CatalogCacheKey
import tachiyomi.domain.tsuzuki.collections.cache.CatalogCacheLookup
import tachiyomi.domain.tsuzuki.collections.cache.PersistentCatalogCacheStore
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.planner.QueryPlanner
import tachiyomi.domain.tsuzuki.collections.planner.SortPlan
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import java.util.concurrent.CancellationException
import kotlin.time.Clock

class ExecuteCollectionList internal constructor(
    private val store: CollectionStore,
    private val providerRegistry: CollectionQueryProviderRegistry,
    private val persistentCache: PersistentCatalogCacheStore,
    private val resources: CollectionExecutionResources,
    private val clock: () -> Long,
) {

    @Inject
    constructor(
        store: CollectionStore,
        providerRegistry: CollectionQueryProviderRegistry,
        persistentCache: PersistentCatalogCacheStore,
        resources: CollectionExecutionResources,
    ) : this(
        store = store,
        providerRegistry = providerRegistry,
        persistentCache = persistentCache,
        resources = resources,
        clock = { Clock.System.now().toEpochMilliseconds() },
    )

    suspend fun execute(request: ExecuteCollectionListRequest): ExecuteCollectionListResult {
        val list = store.getList(request.listId)
            ?: return ExecuteCollectionListResult.DefinitionUnavailable(
                listId = request.listId,
                reason = "List does not exist",
            )

        if (list.deletedAt != null || !list.enabled) {
            return ExecuteCollectionListResult.DefinitionUnavailable(
                listId = request.listId,
                reason = if (list.deletedAt != null) "List is deleted" else "List is disabled",
            )
        }

        val folder = store.getFolder(list.folderId)
        if (folder == null || folder.deletedAt != null || folder.collectionId != list.collectionId) {
            return ExecuteCollectionListResult.DefinitionUnavailable(
                listId = request.listId,
                reason = "List folder is unavailable",
            )
        }

        val collection = store.getCollection(list.collectionId)
        if (collection == null || collection.deletedAt != null) {
            return ExecuteCollectionListResult.DefinitionUnavailable(
                listId = request.listId,
                reason = "List Collection is unavailable",
            )
        }

        val provider = providerRegistry.get(list.providerId)
            ?: return ExecuteCollectionListResult.ProviderUnavailable(list.providerId)

        val plan = QueryPlanner.plan(
            expression = list.query,
            capabilities = provider.capabilities,
            requestedSort = list.sort,
        )
        val remoteSort = when (val sortPlan = plan.sortPlan) {
            is SortPlan.RemoteExact -> sortPlan.sort
            is SortPlan.UnsupportedForGlobalOrdering -> {
                return ExecuteCollectionListResult.UnsupportedGlobalSort(sortPlan.requestedSort)
            }
        }

        when (val support = ResidualEvaluator.support(plan.residualExpression)) {
            ResidualSupport.Supported -> Unit
            is ResidualSupport.Unsupported -> {
                return ExecuteCollectionListResult.UnsupportedResidual(support.reasons)
            }
        }

        val scheduled = resources.scheduler.schedule(
            providerId = provider.providerId,
            priority = request.priority,
        ) {
            executeScheduled(
                request = request,
                list = list,
                provider = provider,
                pushdownExpression = plan.pushdownExpression,
                residualExpression = plan.residualExpression,
                remoteSort = remoteSort,
            )
        }

        return try {
            scheduled.await()
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                scheduled.cancel()
            }
            throw cancellation
        }
    }

    suspend operator fun invoke(request: ExecuteCollectionListRequest): ExecuteCollectionListResult {
        return execute(request)
    }

    private suspend fun executeScheduled(
        request: ExecuteCollectionListRequest,
        list: CollectionList,
        provider: CollectionQueryProvider,
        pushdownExpression: QueryExpression?,
        residualExpression: QueryExpression?,
        remoteSort: CatalogSort,
    ): ExecuteCollectionListResult {
        val result = ResidualPaginator.loadPage(
            residualExpression = residualExpression,
            logicalPageSize = request.pageSize,
            cursor = request.cursor,
            maxProviderPageSize = provider.capabilities.maxPageSize,
            fetcher = CatalogPageFetcher { offset, limit ->
                loadRawPage(
                    provider = provider,
                    pushdownExpression = pushdownExpression,
                    sort = remoteSort,
                    offset = offset,
                    limit = limit,
                    policy = request.cachePolicy,
                )
            },
        )

        return when (result) {
            is ResidualPageResult.Success -> ExecuteCollectionListResult.Page(
                list = list,
                page = result.page,
            )
            is ResidualPageResult.UnsupportedResidual -> {
                ExecuteCollectionListResult.UnsupportedResidual(result.reasons)
            }
            is ResidualPageResult.PaginationInvariantFailure -> {
                ExecuteCollectionListResult.PaginationInvariantFailure(result.reason)
            }
            is ResidualPageResult.ProviderFailure -> {
                if (result.cause is CollectionCacheMissException) {
                    ExecuteCollectionListResult.CacheMiss
                } else {
                    ExecuteCollectionListResult.ProviderFailure(result.cause)
                }
            }
        }
    }

    private suspend fun loadRawPage(
        provider: CollectionQueryProvider,
        pushdownExpression: QueryExpression?,
        sort: CatalogSort,
        offset: Int,
        limit: Int,
        policy: CollectionExecutionCachePolicy,
    ): Result<CatalogPage> {
        val key = CatalogCacheKey.fromExpression(
            providerId = provider.providerId,
            expression = pushdownExpression,
            sort = sort,
            rawOffset = offset,
            pageSize = limit,
        )

        if (policy.mode != CollectionCacheMode.NETWORK_ONLY) {
            val now = clock()

            when (val memory = resources.memoryCache.get(key, now)) {
                CatalogCacheLookup.Miss -> Unit
                is CatalogCacheLookup.Hit -> {
                    if (memory.freshness == CacheFreshness.STALE && policy.mode == CollectionCacheMode.CACHE_FIRST) {
                        requestRefresh(key, provider, pushdownExpression, sort, offset, limit, policy)
                    }
                    return Result.success(memory.page)
                }
            }

            when (val persisted = safePersistentGet(key, now)) {
                CatalogCacheLookup.Miss -> Unit
                is CatalogCacheLookup.Hit -> {
                    if (persisted.freshness == CacheFreshness.STALE && policy.mode == CollectionCacheMode.CACHE_FIRST) {
                        requestRefresh(key, provider, pushdownExpression, sort, offset, limit, policy)
                    }
                    return Result.success(persisted.page)
                }
            }

            if (policy.mode == CollectionCacheMode.CACHE_ONLY) {
                return Result.failure(CollectionCacheMissException())
            }
        }

        return fetchNetwork(
            key = key,
            provider = provider,
            pushdownExpression = pushdownExpression,
            sort = sort,
            offset = offset,
            limit = limit,
            policy = policy,
        )
    }

    private suspend fun requestRefresh(
        key: CatalogCacheKey,
        provider: CollectionQueryProvider,
        pushdownExpression: QueryExpression?,
        sort: CatalogSort,
        offset: Int,
        limit: Int,
        policy: CollectionExecutionCachePolicy,
    ) {
        resources.refreshCoordinator.request(
            key = key,
            providerId = provider.providerId,
        ) {
            fetchNetwork(
                key = key,
                provider = provider,
                pushdownExpression = pushdownExpression,
                sort = sort,
                offset = offset,
                limit = limit,
                policy = policy,
            )
        }
    }

    private suspend fun fetchNetwork(
        key: CatalogCacheKey,
        provider: CollectionQueryProvider,
        pushdownExpression: QueryExpression?,
        sort: CatalogSort,
        offset: Int,
        limit: Int,
        policy: CollectionExecutionCachePolicy,
    ): Result<CatalogPage> {
        val result = resources.inFlightDeduplicator.execute(key) {
            provider.fetch(
                pushdownExpression = pushdownExpression,
                sort = sort,
                offset = offset,
                limit = limit,
            )
        }

        val page = result.getOrNull() ?: return result
        val fetchedAt = clock()
        writeCaches(
            key = key,
            page = page,
            fetchedAt = fetchedAt,
            policy = policy,
        )
        return result
    }

    private suspend fun safePersistentGet(
        key: CatalogCacheKey,
        now: Long,
    ): CatalogCacheLookup {
        return try {
            persistentCache.get(key, now)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            CatalogCacheLookup.Miss
        }
    }

    private suspend fun writeCaches(
        key: CatalogCacheKey,
        page: CatalogPage,
        fetchedAt: Long,
        policy: CollectionExecutionCachePolicy,
    ) {
        try {
            resources.memoryCache.put(
                key = key,
                page = page,
                fetchedAt = fetchedAt,
                ttlMillis = policy.ttlMillis,
                staleWhileRevalidateMillis = policy.staleWhileRevalidateMillis,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            Unit
        }

        try {
            persistentCache.put(
                key = key,
                page = page,
                fetchedAt = fetchedAt,
                ttlMillis = policy.ttlMillis,
                staleWhileRevalidateMillis = policy.staleWhileRevalidateMillis,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            Unit
        }
    }
}
