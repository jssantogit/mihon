package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import kotlin.coroutines.cancellation.CancellationException

@Inject
class GetHomeHero(
    private val integrationRegistry: IntegrationRegistry,
) {

    suspend fun await(limit: Int = DEFAULT_LIMIT): CatalogItem? {
        require(limit > 0) { "Hero discovery limit must be positive" }

        val provider = integrationRegistry.discoveryProviders().firstOrNull()
            ?: return null
        val items = try {
            provider.trending(offset = 0, limit = limit)
                .getOrNull()
                ?.items
                .orEmpty()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            emptyList()
        }

        return items.firstOrNull { !it.bannerUrl.isNullOrBlank() }
            ?: items.firstOrNull { !it.coverUrl.isNullOrBlank() }
    }

    private companion object {
        const val DEFAULT_LIMIT = 8
    }
}
