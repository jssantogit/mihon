package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.model.CanonicalTitle

@Inject
class MaterializeCanonicalTitleFromCatalog(
    private val materializeCanonicalTitle: MaterializeCanonicalTitle,
) {

    suspend fun execute(catalogItem: CatalogItem): CanonicalTitle {
        return materializeCanonicalTitle.fromCatalog(
            displayTitle = catalogItem.title,
            provider = catalogItem.provider,
            externalId = catalogItem.providerId,
        )
    }
}
