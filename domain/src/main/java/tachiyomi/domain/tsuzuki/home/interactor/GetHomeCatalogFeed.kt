package tachiyomi.domain.tsuzuki.home.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider
import tachiyomi.domain.tsuzuki.home.model.HomeCatalogFeed
import kotlin.coroutines.cancellation.CancellationException

@Inject
class GetHomeCatalogFeed(
    private val catalogProvider: CatalogProvider,
) {

    suspend fun execute(limit: Int = 12): HomeCatalogFeed = coroutineScope {
        val recent = async {
            guarded {
                catalogProvider.search(
                    CatalogQuery(
                        sort = CatalogSort.UPDATED_DESC,
                        limit = limit,
                    ),
                )
            }
        }
        val trending = async {
            guarded { catalogProvider.getTrending(limit = limit) }
        }
        val popular = async {
            guarded { catalogProvider.getPopular(limit = limit) }
        }

        HomeCatalogFeed(
            recentlyUpdated = recent.await(),
            trending = trending.await(),
            popular = popular.await(),
        )
    }

    private suspend fun guarded(
        block: suspend () -> Result<tachiyomi.domain.tsuzuki.catalog.model.CatalogPage>,
    ): Result<tachiyomi.domain.tsuzuki.catalog.model.CatalogPage> {
        return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }
}
