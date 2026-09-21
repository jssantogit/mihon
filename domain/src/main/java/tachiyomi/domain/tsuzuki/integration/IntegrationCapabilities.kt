package tachiyomi.domain.tsuzuki.integration

import tachiyomi.domain.tsuzuki.catalog.model.CatalogItem
import tachiyomi.domain.tsuzuki.catalog.model.CatalogPage
import tachiyomi.domain.tsuzuki.catalog.model.CatalogQuery
import tachiyomi.domain.tsuzuki.chapter.evidence.ChapterEvidence
import tachiyomi.domain.tsuzuki.integration.model.ExternalRating
import tachiyomi.domain.tsuzuki.integration.model.TrackingUpdate

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
}

interface TrackingProvider {
    val integrationId: IntegrationId

    suspend fun isConnected(): Boolean

    suspend fun update(update: TrackingUpdate): Result<Unit>
}
