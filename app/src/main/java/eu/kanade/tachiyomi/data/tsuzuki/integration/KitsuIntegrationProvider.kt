package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.Inject
import tachiyomi.data.tsuzuki.kitsu.KitsuCatalogProvider
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider

/**
 * Exposes Kitsu's catalog capabilities without making it a chapter authority.
 *
 * The legacy [KitsuCatalogProvider] remains available for existing catalog callers until the
 * registry migration is complete. This adapter is the explicit Integration-facing boundary.
 */
@Inject
class KitsuIntegrationProvider(
    private val delegate: KitsuCatalogProvider,
) : SearchProvider, DiscoveryProvider, MetadataProvider {

    override val integrationId: IntegrationId = IntegrationId("kitsu")

    override suspend fun search(query: CatalogQuery) = delegate.search(query)

    override suspend fun trending(offset: Int, limit: Int): Result<CatalogPage> =
        delegate.getTrending(offset, limit)

    override suspend fun popular(offset: Int, limit: Int): Result<CatalogPage> =
        delegate.getPopular(offset, limit)

    override suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage> =
        delegate.search(
            CatalogQuery(
                sort = CatalogSort.UPDATED_DESC,
                offset = offset,
                limit = limit,
            ),
        )

    override suspend fun getDetails(externalId: String) = delegate.getDetails(externalId)
}
