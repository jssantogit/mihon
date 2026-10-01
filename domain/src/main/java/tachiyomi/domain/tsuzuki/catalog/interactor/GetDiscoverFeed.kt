package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.catalog.model.CatalogError
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.DiscoverFeed
import tachiyomi.domain.tsuzuki.integration.IntegrationRegistry
import kotlin.coroutines.cancellation.CancellationException

class GetDiscoverFeed private constructor(
    private val integrationRegistry: IntegrationRegistry,
    private val searchIntegrations: SearchIntegrations,
) {

    @Inject
    constructor(integrationRegistry: IntegrationRegistry) : this(
        integrationRegistry = integrationRegistry,
        searchIntegrations = SearchIntegrations(integrationRegistry),
    )

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
                enrichRatings(
                    provider.trending(offset = 0, limit = trendingLimit),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        val popularDeferred = async {
            try {
                enrichRatings(
                    provider.popular(offset = 0, limit = popularLimit),
                )
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

    private suspend fun enrichRatings(result: Result<CatalogPage>): Result<CatalogPage> {
        val error = result.exceptionOrNull()
        if (error is CancellationException) throw error
        if (error != null) return Result.failure(error)

        val page = result.getOrThrow()
        return Result.success(
            page.copy(
                items = searchIntegrations.enrichRatings(page.items),
            ),
        )
    }

    suspend operator fun invoke(
        trendingLimit: Int = 10,
        popularLimit: Int = 20,
    ): DiscoverFeed = await(trendingLimit, popularLimit)

    suspend fun execute(limit: Int = 20): DiscoverFeed = await(trendingLimit = limit, popularLimit = limit)
}
