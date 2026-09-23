package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.DiscoverFeed
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import kotlin.coroutines.cancellation.CancellationException

@Inject
class GetDiscoverFeed(
    private val integrationRegistry: IntegrationRegistry,
) {

    suspend fun await(
        trendingLimit: Int = 10,
        popularLimit: Int = 20,
    ): DiscoverFeed = coroutineScope {
        val provider = integrationRegistry.discoveryProviders().firstOrNull()
        if (provider == null) {
            val error = CatalogError.ProviderUnavailable("No discovery Integration is enabled")
            return@coroutineScope DiscoverFeed(
                trending = Result.failure(error),
                popular = Result.failure(error),
            )
        }

        val trendingDeferred = async {
            try {
                provider.trending(offset = 0, limit = trendingLimit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        val popularDeferred = async {
            try {
                provider.popular(offset = 0, limit = popularLimit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }

        DiscoverFeed(
            trending = trendingDeferred.await(),
            popular = popularDeferred.await(),
        )
    }

    suspend operator fun invoke(
        trendingLimit: Int = 10,
        popularLimit: Int = 20,
    ): DiscoverFeed = await(trendingLimit, popularLimit)

    suspend fun execute(limit: Int = 20): DiscoverFeed = await(trendingLimit = limit, popularLimit = limit)
}
