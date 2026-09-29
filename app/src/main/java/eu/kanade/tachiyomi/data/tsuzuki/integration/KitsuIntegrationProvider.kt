package eu.kanade.tachiyomi.data.tsuzuki.integration

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import tachiyomi.data.tsuzuki.kitsu.KitsuCatalogProvider
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.catalog.model.CatalogSort
import tachiyomi.domain.tsuzuki.integration.DiscoveryProvider
import tachiyomi.domain.tsuzuki.integration.IntegrationId
import tachiyomi.domain.tsuzuki.integration.MetadataProvider
import tachiyomi.domain.tsuzuki.integration.RatingsProvider
import tachiyomi.domain.tsuzuki.integration.SearchProvider
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating

/**
 * Exposes Kitsu's catalog capabilities without making it a chapter authority.
 *
 * The legacy [KitsuCatalogProvider] remains available for compatibility while remaining catalog
 * callers migrate to Integration capabilities.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesIntoSet(AppScope::class, binding = binding<SearchProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<DiscoveryProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<MetadataProvider>())
@ContributesIntoSet(AppScope::class, binding = binding<RatingsProvider>())
class KitsuIntegrationProvider(
    private val delegate: KitsuCatalogProvider,
) : SearchProvider, DiscoveryProvider, MetadataProvider, RatingsProvider {

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

    override suspend fun ratings(externalId: String): Result<List<ExternalRating>> =
        delegate.getDetails(externalId).map { item ->
            listOfNotNull(
                item.score?.let { score ->
                    ExternalRating(
                        providerId = integrationId.value,
                        label = "Kitsu",
                        value = score.value,
                        scaleMax = score.maxValue,
                    )
                },
            )
        }
}
