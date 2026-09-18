package tachiyomi.domain.tsuzuki.catalog.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.interactor.MaterializeCanonicalTitle
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import kotlin.coroutines.cancellation.CancellationException

@Inject
class MaterializeCanonicalTitleFromCatalog(
    private val materializeCanonicalTitle: MaterializeCanonicalTitle,
) {

    suspend fun await(catalogItem: CatalogItem): Result<CanonicalTitle> = runCatching {
        invoke(catalogItem)
    }.onFailure { e ->
        if (e is CancellationException) throw e
    }

    suspend operator fun invoke(catalogItem: CatalogItem): CanonicalTitle {
        return materializeCanonicalTitle.fromCatalog(
            displayTitle = catalogItem.title,
            provider = catalogItem.provider,
            externalId = catalogItem.providerId,
        )
    }

    suspend fun execute(catalogItem: CatalogItem): CanonicalTitle {
        return invoke(catalogItem)
    }
}
