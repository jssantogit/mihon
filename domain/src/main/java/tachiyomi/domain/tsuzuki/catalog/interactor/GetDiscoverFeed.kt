package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.catalog.model.DiscoverFeed
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider
import kotlin.coroutines.cancellation.CancellationException

@Inject
class GetDiscoverFeed(
    private val catalogProvider: CatalogProvider,
) {

    suspend fun await(
        trendingLimit: Int = 10,
        popularLimit: Int = 20,
    ): DiscoverFeed = coroutineScope {
        val trendingDeferred = async {
            try {
                catalogProvider.getTrending(limit = trendingLimit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
        val popularDeferred = async {
            try {
                catalogProvider.getPopular(limit = popularLimit)
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
