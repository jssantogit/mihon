package tachiyomi.domain.tsuzuki.integration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface IntegrationRegistry {
    suspend fun awaitReady() = Unit

    fun observeChanges(): Flow<Unit> = emptyFlow()

    fun searchProviders(): List<SearchProvider>

    fun discoveryProviders(): List<DiscoveryProvider>

    fun metadataProviders(): List<MetadataProvider>

    fun chapterEvidenceProviders(): List<ChapterEvidenceProvider>

    fun ratingsProviders(): List<RatingsProvider>

    fun trackingProviders(): List<TrackingProvider>
}
