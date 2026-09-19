package tachiyomi.domain.tsuzuki.collections.execution

import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.model.CollectionList
import tachiyomi.domain.tsuzuki.collections.scheduler.QuerySchedulePriority

enum class CollectionCacheMode {
    CACHE_FIRST,
    CACHE_ONLY,
    NETWORK_ONLY,
}

data class CollectionExecutionCachePolicy(
    val mode: CollectionCacheMode = CollectionCacheMode.CACHE_FIRST,
    val ttlMillis: Long = 5 * 60 * 1000L,
    val staleWhileRevalidateMillis: Long = 60 * 60 * 1000L,
) {
    init {
        require(ttlMillis >= 0) { "Collection cache TTL cannot be negative" }
        require(staleWhileRevalidateMillis >= 0) {
            "Collection cache stale-while-revalidate window cannot be negative"
        }
    }
}

data class ExecuteCollectionListRequest(
    val listId: String,
    val pageSize: Int = 20,
    val cursor: ResidualPageCursor = ResidualPageCursor(),
    val cachePolicy: CollectionExecutionCachePolicy = CollectionExecutionCachePolicy(),
    val priority: QuerySchedulePriority = QuerySchedulePriority.VISIBLE,
) {
    init {
        require(listId.isNotBlank()) { "Collection List id cannot be blank" }
        require(pageSize > 0) { "Collection logical page size must be positive" }
    }
}

sealed interface ExecuteCollectionListResult {
    data class Page(
        val list: CollectionList,
        val page: LogicalCatalogPage,
    ) : ExecuteCollectionListResult

    data class DefinitionUnavailable(
        val listId: String,
        val reason: String,
    ) : ExecuteCollectionListResult

    data class ProviderUnavailable(
        val providerId: String,
    ) : ExecuteCollectionListResult

    data class UnsupportedGlobalSort(
        val sort: CatalogSort,
    ) : ExecuteCollectionListResult

    data class UnsupportedResidual(
        val reasons: List<String>,
    ) : ExecuteCollectionListResult

    data object CacheMiss : ExecuteCollectionListResult

    data class ProviderFailure(
        val cause: Throwable,
    ) : ExecuteCollectionListResult

    data class PaginationInvariantFailure(
        val reason: String,
    ) : ExecuteCollectionListResult
}

internal class CollectionCacheMissException : IllegalStateException("Collection raw page is not available in cache")
