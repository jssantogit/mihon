package tachiyomi.domain.tsuzuki.collections.capability

import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.collections.query.QueryField
import tachiyomi.domain.tsuzuki.collections.query.QueryOperator
import tachiyomi.domain.tsuzuki.collections.query.QueryValue

/**
 * Capability declaration for a catalog provider describing which parts of a query AST,
 * sorting modes, and paging mechanisms can be pushed down to the remote service.
 */
interface ProviderQueryCapabilities {
    val providerId: String

    /**
     * Determines whether a specific predicate can be pushed down to the remote provider.
     */
    fun canPushPredicate(field: QueryField, operator: QueryOperator, value: QueryValue): Boolean

    /**
     * Determines whether a specific sort configuration can be pushed down for global remote ordering.
     */
    fun canPushSort(sort: CatalogSort): Boolean

    /**
     * Whether the provider supports offset-based pagination.
     */
    val supportsOffsetPaging: Boolean

    /**
     * Maximum page limit accepted by the remote provider, if any.
     */
    val maxPageSize: Int?
}
