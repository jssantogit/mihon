package tachiyomi.domain.tsuzuki.interactor

import dev.zacsweers.metro.Inject
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.model.CanonicalTitle
import tachiyomi.domain.tsuzuki.repository.CanonicalTitleRepository

@Inject
class ReconcileExternalSearchCandidate(
    private val repository: CanonicalTitleRepository,
) {

    suspend fun execute(candidate: CatalogItem): CanonicalTitle? {
        return repository.getByExternalIdentity(
            provider = candidate.provider,
            externalId = candidate.providerId,
        )
    }
}
