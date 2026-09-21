package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry

@Inject
class SearchIntegrations(
    private val registry: IntegrationRegistry,
) {

    suspend fun execute(query: CatalogQuery): List<CatalogItem> = coroutineScope {
        registry.searchProviders()
            .map { provider ->
                async {
                    provider.search(query)
                        .getOrElse { CatalogPage(items = emptyList(), hasNextPage = false) }
                        .items
                }
            }
            .awaitAll()
            .flatten()
    }
}
