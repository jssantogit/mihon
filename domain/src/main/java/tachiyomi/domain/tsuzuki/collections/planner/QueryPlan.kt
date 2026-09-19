package tachiyomi.domain.tsuzuki.collections.planner

import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression

/**
 * Execution plan resulting from splitting a normalized query expression against provider capabilities.
 *
 * @property pushdownExpression The subset of the AST that can be safely evaluated remotely by the provider.
 *                              Null if no predicates can be pushed.
 * @property residualExpression The subset of the AST that must be evaluated locally in memory.
 *                              Null if the entire query was pushed remotely.
 * @property sortPlan The planned sort execution strategy.
 */
data class QueryPlan(
    val pushdownExpression: QueryExpression?,
    val residualExpression: QueryExpression?,
    val sortPlan: SortPlan,
) {
    val isFullyPushdown: Boolean
        get() = pushdownExpression != null && residualExpression == null

    val isFullyResidual: Boolean
        get() = pushdownExpression == null && residualExpression != null

    val isPassthrough: Boolean
        get() = pushdownExpression == null && residualExpression == null
}

/**
 * Strategy for handling requested sort order.
 */
sealed interface SortPlan {
    /**
     * The requested sort is natively supported by the provider and guarantees global ordering across pages.
     */
    data class RemoteExact(val sort: CatalogSort) : SortPlan

    /**
     * The requested sort cannot be executed remotely for global ordering.
     * Note: In-memory sorting of paginated results cannot guarantee global ordering.
     */
    data class UnsupportedForGlobalOrdering(
        val requestedSort: CatalogSort,
        val fallbackRemoteSort: CatalogSort = CatalogSort.POPULARITY_DESC,
    ) : SortPlan
}
