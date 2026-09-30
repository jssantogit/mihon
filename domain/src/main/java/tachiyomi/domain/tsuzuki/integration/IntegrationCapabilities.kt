package tachiyomi.domain.tsuzuki.integration

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.TrackingUpdate
import tachiyomi.domain.tsuzuki.integration.model.UserLibrarySnapshot

typealias IntegrationId = tachiyomi.domain.tsuzuki.capability.IntegrationId

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

    suspend fun ratingsFor(identities: Map<String, String>): Result<List<ExternalRating>> {
        val externalId = identities[integrationId.value]
            ?: return Result.success(emptyList())
        return ratings(externalId)
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
