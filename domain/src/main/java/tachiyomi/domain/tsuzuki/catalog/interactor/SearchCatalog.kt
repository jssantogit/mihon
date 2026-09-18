package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItemStatus
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.catalog.service.CatalogProvider
import kotlin.coroutines.cancellation.CancellationException

@Inject
class SearchCatalog(
    private val catalogProvider: CatalogProvider,
) {

    suspend fun await(
        query: String? = null,
        sort: CatalogSort = CatalogSort.POPULARITY_DESC,
        offset: Int = 0,
        limit: Int = 20,
        genres: List<String> = emptyList(),
        status: CatalogItemStatus? = null,
    ): Result<CatalogPage> = try {
        catalogProvider.search(
            CatalogQuery(
                query = query,
                sort = sort,
                genres = genres,
                status = status,
                offset = offset,
                limit = limit,
            ),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    suspend operator fun invoke(
        query: String? = null,
        sort: CatalogSort = CatalogSort.POPULARITY_DESC,
        offset: Int = 0,
        limit: Int = 20,
        genres: List<String> = emptyList(),
        status: CatalogItemStatus? = null,
    ): Result<CatalogPage> = await(
        query = query,
        sort = sort,
        offset = offset,
        limit = limit,
        genres = genres,
        status = status,
    )

    suspend fun execute(
        query: String? = null,
        offset: Int = 0,
        limit: Int = 20,
        sort: CatalogSort = CatalogSort.POPULARITY_DESC,
    ): Result<CatalogPage> = await(
        query = query,
        sort = sort,
        offset = offset,
        limit = limit,
    )
}
