package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import kotlin.coroutines.cancellation.CancellationException

/** Selects lightweight provider-neutral Home highlights without enriching or executing Collections. */
@Inject
class GetHomeHero(
    private val integrationRegistry: IntegrationRegistry,
) {

    suspend fun await(
        limit: Int = DEFAULT_LIMIT,
        count: Int = DEFAULT_COUNT,
    ): List<CatalogItem> {
        require(limit > 0) { "Hero discovery limit must be positive" }
        require(count > 0) { "Hero candidate count must be positive" }

        val provider = integrationRegistry.discoveryProviders().firstOrNull()
            ?: return emptyList()
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

        val unique = items.distinctBy { it.provider to it.providerId }
        val banners = unique.filter { !it.bannerUrl.isNullOrBlank() }
        val coverOnly = unique.filter {
            it.bannerUrl.isNullOrBlank() && !it.coverUrl.isNullOrBlank()
        }

        return (banners + coverOnly).take(count)
    }

    private companion object {
        const val DEFAULT_LIMIT = 12
        const val DEFAULT_COUNT = 4
    }
}
