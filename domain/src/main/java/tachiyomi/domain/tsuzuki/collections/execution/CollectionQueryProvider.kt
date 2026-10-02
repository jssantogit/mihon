package tachiyomi.domain.tsuzuki.collections.execution

import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression

interface CollectionQueryProvider {
    val providerId: String
    val capabilities: ProviderQueryCapabilities

    suspend fun fetch(
        pushdownExpression: QueryExpression?,
        sort: CollectionSortSelection,
        offset: Int,
        limit: Int,
    ): Result<CatalogPage>
}

interface CollectionQueryProviderRegistry {
    fun get(providerId: String): CollectionQueryProvider?
}
