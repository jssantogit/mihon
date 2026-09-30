package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.mergeCatalogItemsByVerifiedIdentity
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry

@Inject
class SearchIntegrations(
    private val registry: IntegrationRegistry,
    private val enrichCatalogRatings: EnrichCatalogRatings,
) {

    constructor(registry: IntegrationRegistry) : this(
        registry = registry,
        enrichCatalogRatings = EnrichCatalogRatings(registry),
    )

    suspend fun execute(query: CatalogQuery): List<CatalogItem> = coroutineScope {
        val merged = registry.searchProviders()
            .map { provider ->
                async {
                    provider.search(query)
                        .getOrElse { CatalogPage(items = emptyList(), hasNextPage = false) }
                        .items
                }
            }
            .awaitAll()
            .flatten()
            // Exact provider ids and provider-published cross-provider mappings are safe.
            // Title similarity alone remains intentionally insufficient.
            .let(::mergeCatalogItemsByVerifiedIdentity)

        enrichCatalogRatings.execute(merged)
    }

    suspend fun enrichRatings(items: List<CatalogItem>): List<CatalogItem> =
        enrichCatalogRatings.execute(items)
}
