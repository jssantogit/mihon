package tachiyomi.domain.tsuzuki.collections.execution

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.collections.cache.CacheFreshness
import tachiyomi.domain.tsuzuki.collections.cache.CatalogCacheKey
import tachiyomi.domain.tsuzuki.collections.cache.CatalogCacheLookup
import tachiyomi.domain.tsuzuki.collections.cache.PersistentCatalogCacheStore
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.planner.QueryPlanner
import tachiyomi.domain.tsuzuki.collections.planner.SortPlan
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression
import tachiyomi.domain.tsuzuki.collections.repository.CollectionStore
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttribute
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticAttributeValue
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticEventName
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticOutcome
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticStage
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticSubsystem
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticTrace
import tachiyomi.domain.tsuzuki.diagnostics.DiagnosticWorkflow
import tachiyomi.domain.tsuzuki.diagnostics.NoOpStructuredDiagnosticRecorder
import tachiyomi.domain.tsuzuki.diagnostics.StructuredDiagnosticRecorder
import java.util.concurrent.CancellationException
import kotlin.time.Clock

class ExecuteCollectionList internal constructor(
    private val store: CollectionStore,
    private val providerRegistry: CollectionQueryProviderRegistry,
    private val persistentCache: PersistentCatalogCacheStore,
    private val resources: CollectionExecutionResources,
    private val clock: () -> Long,
    private val diagnosticRecorder: StructuredDiagnosticRecorder = NoOpStructuredDiagnosticRecorder,
) {

    @Inject
    constructor(
        store: CollectionStore,
        providerRegistry: CollectionQueryProviderRegistry,
        persistentCache: PersistentCatalogCacheStore,
        resources: CollectionExecutionResources,
        diagnosticRecorder: StructuredDiagnosticRecorder,
    ) : this(
        store = store,
        providerRegistry = providerRegistry,
        persistentCache = persistentCache,
        resources = resources,
        clock = { Clock.System.now().toEpochMilliseconds() },
        diagnosticRecorder = diagnosticRecorder,
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

        val draftResult = executeDraft(
            ExecuteCollectionDraftRequest(
                draft = CollectionListDraftExecution(
                    providerId = list.providerId,
                    query = list.query,
                    sort = list.sort,
                ),
                pageSize = request.pageSize,
                cursor = request.cursor,
                cachePolicy = request.cachePolicy,
                priority = request.priority,
            ),
        )

        return when (draftResult) {
            is ExecuteCollectionDraftResult.Page -> ExecuteCollectionListResult.Page(
                list = list,
                page = draftResult.page,
            )
            is ExecuteCollectionDraftResult.ProviderUnavailable ->
                ExecuteCollectionListResult.ProviderUnavailable(draftResult.providerId)
            is ExecuteCollectionDraftResult.UnsupportedGlobalSort ->
                ExecuteCollectionListResult.UnsupportedGlobalSort(draftResult.sort)
            is ExecuteCollectionDraftResult.UnsupportedResidual ->
                ExecuteCollectionListResult.UnsupportedResidual(draftResult.reasons)
            ExecuteCollectionDraftResult.CacheMiss -> ExecuteCollectionListResult.CacheMiss
            is ExecuteCollectionDraftResult.ProviderFailure ->
                ExecuteCollectionListResult.ProviderFailure(draftResult.cause)
            is ExecuteCollectionDraftResult.PaginationInvariantFailure ->
                ExecuteCollectionListResult.PaginationInvariantFailure(draftResult.reason)
        }
    }

    suspend operator fun invoke(request: ExecuteCollectionListRequest): ExecuteCollectionListResult {
        return execute(request)
    }

    internal suspend fun executeDraft(
        request: ExecuteCollectionDraftRequest,
    ): ExecuteCollectionDraftResult {
        val trace = DiagnosticTrace.start(
            recorder = diagnosticRecorder,
            workflow = DiagnosticWorkflow.COLLECTION_EXECUTION,
            subsystem = DiagnosticSubsystem.COLLECTIONS,
        )
        val provider = providerRegistry.get(request.draft.providerId)
        if (provider == null) {
            val result = ExecuteCollectionDraftResult.ProviderUnavailable(request.draft.providerId)
            recordCollectionResult(trace, request.draft.providerId, result)
            return result
        }

        val plan = QueryPlanner.plan(
            expression = request.draft.query,
            capabilities = provider.capabilities,
            requestedSort = request.draft.sort,
        )
        val remoteSort = when (val sortPlan = plan.sortPlan) {
            is SortPlan.RemoteExact -> sortPlan.sort
            is SortPlan.UnsupportedForGlobalOrdering -> {
                val result = ExecuteCollectionDraftResult.UnsupportedGlobalSort(sortPlan.requestedSort)
                recordCollectionResult(trace, provider.providerId, result)
                return result
            }
        }

        trace.event(
            subsystem = DiagnosticSubsystem.COLLECTIONS,
            name = DiagnosticEventName.COLLECTION_QUERY_PLANNED,
            stage = DiagnosticStage.SEARCH,
            outcome = DiagnosticOutcome.SUCCEEDED,
            attributes = providerAttributes(provider.providerId),
        )

        when (val support = ResidualEvaluator.support(plan.residualExpression)) {
            ResidualSupport.Supported -> Unit
            is ResidualSupport.Unsupported -> {
                val result = ExecuteCollectionDraftResult.UnsupportedResidual(support.reasons)
                recordCollectionResult(trace, provider.providerId, result)
                return result
            }
        }

        val scheduled = resources.scheduler.schedule(
            providerId = provider.providerId,
            priority = request.priority,
        ) {
            executeScheduledDraft(
                request = request,
                provider = provider,
                pushdownExpression = plan.pushdownExpression,
                residualExpression = plan.residualExpression,
                remoteSort = remoteSort,
            )
        }

        return try {
            scheduled.await().also { result ->
                recordCollectionResult(trace, provider.providerId, result)
            }
        } catch (cancellation: CancellationException) {
            trace.event(
                subsystem = DiagnosticSubsystem.COLLECTIONS,
                name = DiagnosticEventName.COLLECTION_EXECUTION_COMPLETED,
                stage = DiagnosticStage.COMPLETE,
                outcome = DiagnosticOutcome.CANCELLED,
                attributes = providerAttributes(provider.providerId),
            )
            withContext(NonCancellable) {
                scheduled.cancel()
            }
            throw cancellation
        }
    }

    private suspend fun executeScheduledDraft(
        request: ExecuteCollectionDraftRequest,
        provider: CollectionQueryProvider,
        pushdownExpression: QueryExpression?,
        residualExpression: QueryExpression?,
        remoteSort: CollectionSortSelection,
    ): ExecuteCollectionDraftResult {
        val result = ResidualPaginator.loadPage(
            residualExpression = residualExpression,
            logicalPageSize = request.pageSize,
            cursor = request.cursor,
            maxProviderPageSize = provider.capabilities.maxPageSize,
            scanPolicy = provider.capabilities.descriptor.scanPolicy,
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
            is ResidualPageResult.Success -> ExecuteCollectionDraftResult.Page(result.page)
            is ResidualPageResult.BudgetReached -> ExecuteCollectionDraftResult.Page(result.page)
            is ResidualPageResult.UnsupportedResidual ->
                ExecuteCollectionDraftResult.UnsupportedResidual(result.reasons)
            is ResidualPageResult.PaginationInvariantFailure ->
                ExecuteCollectionDraftResult.PaginationInvariantFailure(result.reason)
            is ResidualPageResult.ProviderFailure -> {
                if (result.cause is CollectionCacheMissException) {
                    ExecuteCollectionDraftResult.CacheMiss
                } else {
                    ExecuteCollectionDraftResult.ProviderFailure(result.cause)
                }
            }
        }
    }

    private fun recordCollectionResult(
        trace: DiagnosticTrace,
        providerId: String,
        result: ExecuteCollectionDraftResult,
    ) {
        val outcome = when (result) {
            is ExecuteCollectionDraftResult.Page ->
                if (result.page.scanBudgetReason == null) DiagnosticOutcome.SUCCEEDED else DiagnosticOutcome.PARTIAL
            is ExecuteCollectionDraftResult.ProviderUnavailable -> DiagnosticOutcome.NOT_FOUND_NO_CANDIDATES
            is ExecuteCollectionDraftResult.UnsupportedGlobalSort,
            is ExecuteCollectionDraftResult.UnsupportedResidual,
            is ExecuteCollectionDraftResult.PaginationInvariantFailure,
            -> DiagnosticOutcome.REJECTED
            ExecuteCollectionDraftResult.CacheMiss -> DiagnosticOutcome.MISS
            is ExecuteCollectionDraftResult.ProviderFailure -> DiagnosticOutcome.FAILED
        }
        val attributes = buildMap {
            putAll(providerAttributes(providerId))
            if (result is ExecuteCollectionDraftResult.Page) {
                put(
                    DiagnosticAttribute.ITEM_COUNT,
                    DiagnosticAttributeValue.Number(result.page.items.size.toLong()),
                )
            }
        }
        trace.event(
            subsystem = DiagnosticSubsystem.COLLECTIONS,
            name = DiagnosticEventName.COLLECTION_EXECUTION_COMPLETED,
            stage = DiagnosticStage.COMPLETE,
            outcome = outcome,
            attributes = attributes,
        )
    }

    private fun providerAttributes(providerId: String): Map<DiagnosticAttribute, DiagnosticAttributeValue> =
        mapOf(
            DiagnosticAttribute.PROVIDER_ID to DiagnosticAttributeValue.Text(providerId),
        )

    private suspend fun loadRawPage(
        provider: CollectionQueryProvider,
        pushdownExpression: QueryExpression?,
        sort: CollectionSortSelection,
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
        sort: CollectionSortSelection,
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
        sort: CollectionSortSelection,
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
