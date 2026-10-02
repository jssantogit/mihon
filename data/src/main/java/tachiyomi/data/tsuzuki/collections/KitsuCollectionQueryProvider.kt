package tachiyomi.data.tsuzuki.collections

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.tsuzuki.kitsu.KitsuCatalogProvider
import tachiyomi.data.tsuzuki.kitsu.collections.KitsuQueryCapabilities
import tachiyomi.data.tsuzuki.kitsu.collections.KitsuQueryCompiler
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.collections.model.CollectionSortSelection
import tachiyomi.domain.tsuzuki.collections.capability.ProviderQueryCapabilities
import tachiyomi.domain.tsuzuki.collections.execution.CollectionQueryProvider
import tachiyomi.domain.tsuzuki.collections.query.QueryExpression

@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class)
class KitsuCollectionQueryProvider(
    private val provider: KitsuCatalogProvider,
) : CollectionQueryProvider {

    override val providerId: String = KitsuQueryCapabilities.providerId
    override val capabilities: ProviderQueryCapabilities = KitsuQueryCapabilities

    override suspend fun fetch(
        pushdownExpression: QueryExpression?,
        sort: CollectionSortSelection,
        offset: Int,
        limit: Int,
    ): Result<CatalogPage> {
        val query = KitsuQueryCompiler.compile(
            pushdownExpression = pushdownExpression,
            sort = sort,
            offset = offset,
            limit = limit,
        )
        return provider.search(query)
    }
}
