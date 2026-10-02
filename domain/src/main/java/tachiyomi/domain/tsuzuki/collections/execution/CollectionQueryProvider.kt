package tachiyomi.domain.tsuzuki.collections.execution

import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.collections.capability.CollectionProviderDescriptor
import tachiyomi.domain.tsuzuki.collections.capability.FilterOption
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression

/** Provider Capabilities V2 final validation anchor: UI and runtime share this contract. */
interface CollectionQueryProvider {
    val providerId: String
    val capabilities: ProviderQueryCapabilities

    suspend fun fetch(
        pushdownExpression: QueryExpression?,
        sort: CollectionSortSelection,
        offset: Int,
        limit: Int,
    ): Result<CatalogPage>

    suspend fun lookupValues(
        lookupId: String,
        query: String? = null,
    ): Result<List<FilterOption>> = Result.failure(
        UnsupportedOperationException("Provider '$providerId' does not implement lookup '$lookupId'"),
    )
}

interface CollectionQueryProviderRegistry {
    fun get(providerId: String): CollectionQueryProvider?

    fun all(): List<CollectionQueryProvider>

    fun descriptors(): List<CollectionProviderDescriptor> = all().map { it.capabilities.descriptor }

    suspend fun lookupValues(
        providerId: String,
        lookupId: String,
        query: String? = null,
    ): Result<List<FilterOption>> =
        get(providerId)?.lookupValues(lookupId, query)
            ?: Result.failure(IllegalArgumentException("Unknown Collection provider '$providerId'"))
}
