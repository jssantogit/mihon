package tachiyomi.domain.tsuzuki.integration

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.integration.model.CatalogRatingMatch
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.TrackingUpdate
import tachiyomi.domain.tsuzuki.integration.model.UserLibrarySnapshot

typealias IntegrationId = tachiyomi.domain.tsuzuki.capability.IntegrationId

val TSUZUKI_INTEGRATION_ID = IntegrationId("tsuzuki")

interface SearchProvider {
    val integrationId: IntegrationId

    suspend fun search(query: CatalogQuery): Result<CatalogPage>
}

interface DiscoveryProvider {
    val integrationId: IntegrationId

    suspend fun trending(offset: Int, limit: Int): Result<CatalogPage>

    suspend fun popular(offset: Int, limit: Int): Result<CatalogPage>

    suspend fun recentlyUpdated(offset: Int, limit: Int): Result<CatalogPage>

    suspend fun topRated(offset: Int, limit: Int): Result<CatalogPage> =
        Result.success(CatalogPage(items = emptyList(), hasNextPage = false))

    suspend fun favorites(offset: Int, limit: Int): Result<CatalogPage> =
        Result.success(CatalogPage(items = emptyList(), hasNextPage = false))
}

interface MetadataProvider {
    val integrationId: IntegrationId

    suspend fun getDetails(externalId: String): Result<CatalogItem>
}

interface ChapterEvidenceProvider {
    val producerId: String

    suspend fun evidenceFor(canonicalTitleId: String): Result<List<ChapterEvidence>>
}

interface RatingsProvider {
    val integrationId: IntegrationId

    suspend fun ratings(externalId: String): Result<List<ExternalRating>>

    /**
     * Adds only identities that can be proven by provider IDs or provider-published mappings.
     * Implementations may use title search to find candidates, but title equality alone must never
     * be returned as identity evidence.
     */
    suspend fun resolveExternalIds(item: CatalogItem): Result<Map<String, String>> {
        val externalId = when {
            item.provider == integrationId.value -> item.providerId
            else -> item.externalIds[integrationId.value]
        }?.takeIf(String::isNotBlank) ?: return Result.success(emptyMap())

        return Result.success(mapOf(integrationId.value to externalId))
    }

    /**
     * Resolves this provider's rating for a catalog work without treating the catalog provider as
     * rating authority. The default path only accepts an exact known provider identity.
     *
     * Implementations may additionally return a strict corroborated rating-only match with
     * [CatalogRatingMatch.verifiedIdentity] = false. Such a match may be displayed as rating
     * provenance but must never be promoted into canonical cross-provider identity.
     */
    suspend fun ratingFor(item: CatalogItem): Result<CatalogRatingMatch?> {
        val externalId = when {
            item.provider == integrationId.value -> item.providerId
            else -> item.externalIds[integrationId.value]
        }?.takeIf(String::isNotBlank) ?: return Result.success(null)

        return ratings(externalId).map { values ->
            values.firstOrNull()?.let { rating ->
                CatalogRatingMatch(
                    externalId = externalId,
                    rating = rating,
                )
            }
        }
    }
}

interface TrackingProvider {
    val integrationId: IntegrationId

    suspend fun isConnected(): Boolean

    suspend fun update(update: TrackingUpdate): Result<Unit>
}

interface UserListProvider {
    val integrationId: IntegrationId

    val connection: Flow<Boolean>

    suspend fun fetchLibrary(): Result<UserLibrarySnapshot>
}
